// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.NUM_JOBS;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPrefixConflictException;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode;
import com.google.devtools.build.lib.concurrent.ErrorClassifier;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.concurrent.QuiescingExecutor;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.ActionConflictsAndStats;
import com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.ConflictException;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.GuardedBy;

/**
 * An incremental artifact conflict finder that maintains a running state.
 *
 * <p>Once an ActionLookupKey is analyzed, its actions are registered with this conflict finder
 * before execution. The internal action graph accumulates these actions in order to detect a
 * conflict later on. There should be one instance of this class per build.
 */
@ThreadSafe
public final class IncrementalArtifactConflictFinder {
  private final MutableActionGraph threadSafeMutableActionGraph;
  private final ConcurrentMap<String, Object> pathFragmentTrieRoot;
  private final QuiescingExecutor exclusivePool;
  private final ListeningExecutorService freeForAllPool;
  private final WalkableGraph walkableGraph;
  private final AtomicBoolean conflictFound = new AtomicBoolean(false);
  private final Set<ActionLookupKey> globalVisited = Sets.newConcurrentHashSet();

  @GuardedBy("exclusivePool")
  private CountDownLatch nextSignalToWaitFor = null;

  public IncrementalArtifactConflictFinder(
      MutableActionGraph threadSafeMutableActionGraph, WalkableGraph walkableGraph) {
    this.threadSafeMutableActionGraph = threadSafeMutableActionGraph;
    this.pathFragmentTrieRoot = new ConcurrentHashMap<>();
    this.walkableGraph = walkableGraph;
    this.exclusivePool =
        AbstractQueueVisitor.createWithExecutorService(
            Executors.newFixedThreadPool(
                NUM_JOBS, new ThreadFactoryBuilder().setNameFormat("ALV collector %d").build()),
            ExceptionHandlingMode.KEEP_GOING,
            ErrorClassifier.DEFAULT);
    this.freeForAllPool =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                NUM_JOBS,
                new ThreadFactoryBuilder().setNameFormat("Action conflict finder %d").build()));
  }

  public int getOutputArtifactCount() {
    return threadSafeMutableActionGraph.getSize();
  }

  ActionConflictsAndStats findArtifactConflicts(
      ActionLookupKey actionLookupKey, boolean strictConflictChecks) throws InterruptedException {
    return findArtifactConflicts(actionLookupKey, strictConflictChecks, /* inRerun= */ false);
  }

  /**
   * The following scenario would be used for the rest of this section:
   *
   * <ul>
   *   <li>topA depends on C1 and C2,
   *   <li>topB also depends on C1 and C2,
   *   <li>C1 and C2 conflict
   *   <li>--keep_going
   * </ul>
   *
   * With Skymeld, conflict checking has to be done incrementally the moment each top level target's
   * analysis is finished. We're essentially trying to ensure 2 goals: (goal#1) for the "happy
   * path", no extra ALV is traversed and (goal#2) for the conflict case, no top level target is
   * allowed to enter execution without making sure that there's no conflict in its actions. Some
   * past solutions that didn't quite work:
   *
   * <ul>
   *   <li>If we use a naive global set of visited ALKs to prune traversal, we achieve (goal#1) but
   *       fail (goal#2). Explanation below [1].
   *   <li>If we only add ALKs to this set when we know these ALKs are conflict-free, we achieve
   *       (goal#2) but fail (goal#1): if conflict_check(topA) and conflict_check(topB) happen
   *       around the same time, we essentially get no ALV pruning. Also covered below [1].
   * </ul>
   *
   * To achieve both, we use the following algorithm:
   *
   * <pre>{@code
   * 1. [Sequential portion] Sequentially collect the ALVs in the transitive closure of a top level
   *    target. Store the visited keys in a set and use that to exclude them from traversals by
   *    other top level targets.
   *    - The strict sequential ordering ensures that by the time we're done with the conflict check
   *      of a top level target, its full transitive closure is covered and therefore avoiding
   *      missing possible conflicts. More explanation in [2].
   *
   * 2. [Concurrent portion] Concurrently check the actions in the collected ALVs.
   *
   * 3. Finalizing the conflict checking of the ith top level key only if that of the (i - 1)th key
   *    is finalized. Once a key is finalized, we can be sure that it contains no conflict.
   *    - Finalizing, in practice, simply means allowing the conflict checking method to return and
   *      essentially starting the execution.
   *    - The ordering is the order in which top level targets start checking for conflicts.
   *    - The ordering is important for correctness reasons: a top level target needs to wait until
   *      the ALVs that were in the visited set when it started checking for conflicts to have
   *      actually been checked for conflicts.
   *
   * 4. If there's a conflict detected at any point, rerun the check for the unfinished keys without
   *    pruning (the full transitive closure would be visited).
   * }</pre>
   *
   * <p>#1 would ensure (goal#1) since there's pruning. #3 and #4 would ensure (goal#2). #2 is for
   * performance.
   *
   * <p>Why do we need #1 to be sequential? See [2].
   *
   * <p>Why do we need #2 to be a separate concurrent section? Without it, we'd essentially be doing
   * the entire conflict checking sequentially. Our benchmark has shown that this was very slow.
   *
   * <p>Why do we need the ordering in #3? See [3].
   *
   * <p>Why do we need the rerun in #4? Without it, we can't really proceed. Should a top level
   * target topC be stopped from executing by a conflict discovered in topA? We don't have enough
   * information to know without rerunning.
   *
   * <p>=== Footnotes ===
   *
   * <p>[1] Assume the following sequence:
   *
   * <pre>{@code
   * conflict_check(topA)
   * topA visits C1
   * topA visits C2
   *
   * conflict_check(topB)
   * topB doesn't visit C1 & C2 since they're in the visited set
   * check_actions(topB) returns with no conflict
   *
   * check_actions(topA) finally recognizes the conflict, but it's too late. topB already started
   * executing.
   * }</pre>
   *
   * <p>To avoid this issue, we have been only updating the global set with conflict-free keys. This
   * however comes with a heavy performance penalty: if the top level targets start to check for
   * conflicts at roughly the same time, this pruning mechanism is ineffective and would result in a
   * lot more extra work.
   *
   * <p>[2] If #1 isn't sequential, the following can happen:
   *
   * <pre>{@code
   * # conflict_check = collect_alv (concurrent) + check_actions (concurrent)
   * collect_alv(topA)
   * collect_alv(topB)
   *
   * topA visits C1
   * topB visits C2. Since C2 is visited, topA doesn't visit it anymore
   *
   * check_actions(topA) returns with no conflict
   * check_actions(topB) finally recognizes the conflict, but it's too late. topA already started
   * executing.
   * }</pre>
   *
   * What we've ensured here is: if we discover a conflict foo, there's no chance of it being
   * executed by a top level target that's already confirmed to be conflict-free.
   *
   * <p>[3] If the ith key doesn't wait for the (i - 1)th key, the following can happen:
   *
   * <pre>{@code
   * # conflict_check = collect_alv (sequential) + check_actions (concurrent)
   * collect_alv(topA)
   * topA visits C1
   * topA visits C2
   *
   * collect_alv(topB)
   * check_actions(topB) does not wait for top A and returns with no conflict
   *
   * check_actions(topA) finally recognizes the conflict, but it's too late. topB already started
   * executing.
   * }</pre>
   */
  ActionConflictsAndStats findArtifactConflicts(
      ActionLookupKey actionLookupKey, boolean strictConflictChecks, boolean inRerun)
      throws InterruptedException {
    ConcurrentMap<ActionAnalysisMetadata, ConflictException> temporaryBadActionMap =
        new ConcurrentHashMap<>();

    List<ListenableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());

    CountDownLatch toWaitFor = null;
    CountDownLatch mySignal = null;

    // Only allow 1 top-level target to do ALV collection at a time.
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.CONFLICT_CHECK, "ALV collection")) {
      synchronized (exclusivePool) {
        if (!inRerun) {
          toWaitFor = nextSignalToWaitFor;
          mySignal = new CountDownLatch(1);
          nextSignalToWaitFor = mySignal;
        }
        exclusivePool.execute(
            new CheckForConflictsUnderKey(
                actionLookupKey, futures, temporaryBadActionMap, inRerun, strictConflictChecks));
        exclusivePool.awaitQuiescenceWithoutShutdown(true);
      }
    }

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.CONFLICT_CHECK, "Go through actions")) {
      try {
        Futures.whenAllComplete(futures).call(() -> null, directExecutor()).get();
      } catch (ExecutionException e) {
        throw new IllegalStateException("Unexpected exception", e);
      }

      if (!temporaryBadActionMap.isEmpty()) {
        conflictFound.set(true);
      }
    }

    if (!inRerun) {
      // Wait for the previous check in the queue.
      try (SilentCloseable c =
          Profiler.instance()
              .profile(ProfilerTask.CONFLICT_CHECK, "Awaiting signal from a prior key.")) {
        if (toWaitFor != null) {
          toWaitFor.await();
        }
      }

      // Signal the next check in the queue to continue.
      mySignal.countDown();

      // Rerun if there's a conflict and this isn't the rerun already.
      // No need to rerun if the temporaryBadActionMap is non-empty: this means a conflict has
      // been detected for this top level target and it won't be executed. That's all we want.
      if (conflictFound.get() && toWaitFor != null && temporaryBadActionMap.isEmpty()) {
        return findArtifactConflicts(actionLookupKey, strictConflictChecks, /* inRerun= */ true);
      }
    }

    return ActionConflictsAndStats.create(
        ImmutableMap.copyOf(temporaryBadActionMap), threadSafeMutableActionGraph.getSize());
  }

  ActionConflictsAndStats findArtifactConflictsNoIncrementality(
      ImmutableCollection<SkyValue> actionLookupValues, boolean strictConflictChecks)
      throws InterruptedException {
    ConcurrentMap<ActionAnalysisMetadata, ConflictException> temporaryBadActionMap =
        new ConcurrentHashMap<>();

    try (SilentCloseable c =
        Profiler.instance()
            .profile(ProfilerTask.CONFLICT_CHECK, "constructActionGraphAndArtifactList")) {
      constructActionGraphAndArtifactList(
          pathFragmentTrieRoot,
          actionLookupValues,
          strictConflictChecks,
          temporaryBadActionMap);
    }

    return ActionConflictsAndStats.create(
        ImmutableMap.copyOf(temporaryBadActionMap), threadSafeMutableActionGraph.getSize());
  }

  private void constructActionGraphAndArtifactList(
      ConcurrentMap<String, Object> pathFragmentTrieRoot,
      ImmutableCollection<SkyValue> actionLookupValues,
      boolean strictConflictChecks,
      ConcurrentMap<ActionAnalysisMetadata, ConflictException> badActionMap)
      throws InterruptedException {
    List<ListenableFuture<Void>> futures = new ArrayList<>(actionLookupValues.size());
    synchronized (freeForAllPool) {
      // Some other thread shut down the executor, exit now.
      if (freeForAllPool.isShutdown()) {
        return;
      }
      for (SkyValue alv : actionLookupValues) {
        futures.add(
            freeForAllPool.submit(
                () ->
                    actionRegistration(
                        alv,
                        threadSafeMutableActionGraph,
                        pathFragmentTrieRoot,
                        strictConflictChecks,
                        badActionMap)));
      }
    }
    // Now wait on the futures.
    try {
      Futures.whenAllComplete(futures).call(() -> null, directExecutor()).get();
    } catch (ExecutionException e) {
      throw new IllegalStateException("Unexpected exception", e);
    }
  }

  void shutdown() {
    try {
      synchronized (exclusivePool) {
        exclusivePool.awaitQuiescence(true);
      }
    } catch (InterruptedException e) {
      // Preserve the interrupt status.
      Thread.currentThread().interrupt();
    }
    synchronized (freeForAllPool) {
      if (!freeForAllPool.isShutdown() && ExecutorUtil.interruptibleShutdown(freeForAllPool)) {
        // Preserve the interrupt status.
        Thread.currentThread().interrupt();
      }
    }
  }

  private static Void actionRegistration(
      SkyValue value,
      MutableActionGraph actionGraph,
      ConcurrentMap<String, Object> pathFragmentTrieRoot,
      boolean strictConflictChecks,
      ConcurrentMap<ActionAnalysisMetadata, ConflictException> badActionMap) {
    Preconditions.checkState(value instanceof ActionLookupValue);
    for (ActionAnalysisMetadata action : ((ActionLookupValue) value).getActions()) {
      try {
        actionGraph.registerAction(action);
      } catch (ActionConflictException e) {
        // It may be possible that we detect a conflict for the same action more than once, if
        // that action belongs to multiple aspect values. In this case we will harmlessly
        // overwrite the badActionMap entry.
        badActionMap.put(action, new ConflictException(e));
        // We skip the rest of the loop, and do not add the path->artifact mapping for this
        // artifact below -- we don't need to check it since this action is already in
        // error.
        continue;
      } catch (InterruptedException e) {
        // Bail.
        Thread.currentThread().interrupt();
        return null;
      }
      for (Artifact output : action.getOutputs()) {
        checkOutputPrefix(
            actionGraph, strictConflictChecks, pathFragmentTrieRoot, output, badActionMap);
      }
    }
    return null;
  }

  /**
   * Fits the path segments into the existing trie.
   *
   * <p>A conceptual path segment TrieNode can be:
   *
   * <ul>
   *   <li>an Artifact if it's a leaf node, or
   *   <li>a {@code ConcurrentMap<String, Object>} if it's a non-leaf node. The mapping is from a
   *       path segment to another trie node.
   * </ul>
   *
   * <p>We do this instead of creating a proper wrapper TrieNode data structure to save memory, as
   * the trie is expected to get quite large.
   */
  private static void checkOutputPrefix(
      MutableActionGraph actionGraph,
      boolean strictConflictCheck,
      ConcurrentMap<String, Object> root,
      Artifact newArtifact,
      ConcurrentMap<ActionAnalysisMetadata, ConflictException> badActionMap) {
    Object existingTrieNode = root;
    PathFragment newArtifactPathFragment = newArtifact.getExecPath();
    Iterator<String> newPathIter = newArtifactPathFragment.segments().iterator();

    while (newPathIter.hasNext() && !(existingTrieNode instanceof Artifact)) {
      String newSegment = newPathIter.next();
      boolean isFinalSegmentOfNewPath = !newPathIter.hasNext();
      @SuppressWarnings("unchecked")
      ConcurrentMap<String, Object> existingNonLeafNode =
          (ConcurrentMap<String, Object>) existingTrieNode;

      Object matchingChildNode =
          existingNonLeafNode.computeIfAbsent(
              newSegment,
              isFinalSegmentOfNewPath
                  ? unused -> newArtifact
                  : unused -> new ConcurrentHashMap<String, Object>());

      // By the time we arrive in this method, we know for sure that there can't be any exact
      // matches in the paths since that would have been an ActionConflictException.
      boolean newPathIsPrefixOfExisting =
          !(matchingChildNode instanceof Artifact) && isFinalSegmentOfNewPath;
      boolean existingPathIsPrefixOfNew =
          matchingChildNode instanceof Artifact && !isFinalSegmentOfNewPath;

      if (existingPathIsPrefixOfNew || newPathIsPrefixOfExisting) {
        Artifact conflictingExistingArtifact = getOwningArtifactFromTrie(matchingChildNode);
        ActionAnalysisMetadata priorAction =
            Preconditions.checkNotNull(
                actionGraph.getGeneratingAction(conflictingExistingArtifact),
                conflictingExistingArtifact);
        ActionAnalysisMetadata currentAction =
            Preconditions.checkNotNull(actionGraph.getGeneratingAction(newArtifact), newArtifact);
        if (strictConflictCheck || priorAction.shouldReportPathPrefixConflict(currentAction)) {
          ConflictException exception =
              new ConflictException(
                  new ArtifactPrefixConflictException(
                      conflictingExistingArtifact.getExecPath(),
                      newArtifactPathFragment,
                      priorAction.getOwner().getLabel(),
                      currentAction.getOwner().getLabel()));
          badActionMap.put(priorAction, exception);
          badActionMap.put(currentAction, exception);
        }
        // If 2 paths collide, we need to update the Trie to contain only the shorter one.
        // This is required for correctness: the set of subsequent paths that could conflict with
        // the longer path is a subset of that of the shorter path.
        if (newPathIsPrefixOfExisting) {
          existingNonLeafNode.put(newSegment, newArtifact);
        }

        break;
      }
      existingTrieNode = matchingChildNode;
    }
  }

  // TODO(b/214389062) Fix the issue with SolibSymlinkAction before launch.
  private static Artifact getOwningArtifactFromTrie(Object trieNode) {
    Preconditions.checkArgument(
        trieNode instanceof Artifact || trieNode instanceof ConcurrentHashMap);
    if (trieNode instanceof Artifact) {
      return (Artifact) trieNode;
    }
    Object nodeIter = trieNode;
    while (!(nodeIter instanceof Artifact)) {
      // Just pick the first path available down the Trie.
      for (Object value : ((ConcurrentHashMap<?, ?>) nodeIter).values()) {
        nodeIter = value;
        break;
      }
    }
    return (Artifact) nodeIter;
  }

  private boolean shouldVisit(ActionLookupKey actionLookupKey, boolean inRerun) {
    // When in a rerun, visit every node to make sure a conflict is not missed because of pruning.
    return inRerun || globalVisited.add(actionLookupKey);
  }

  /** Visit the transitive closure of {@code key} and check for conflicts among the actions. */
  private final class CheckForConflictsUnderKey implements Runnable {
    private final ActionLookupKey key;
    private final List<ListenableFuture<Void>> futures;
    private final ConcurrentMap<ActionAnalysisMetadata, ConflictException> badActionMap;
    private final boolean inRerun;
    private final boolean strictConflictChecks;

    private CheckForConflictsUnderKey(
        ActionLookupKey key,
        List<ListenableFuture<Void>> futures,
        ConcurrentMap<ActionAnalysisMetadata, ConflictException> badActionMap,
        boolean inRerun,
        boolean strictConflictChecks) {
      this.key = key;
      this.futures = futures;
      this.badActionMap = badActionMap;
      this.inRerun = inRerun;
      this.strictConflictChecks = strictConflictChecks;
    }

    @Override
    public void run() {
      SkyValue value = null;
      try {
        value = walkableGraph.getValue(key);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (value == null) { // The value failed to evaluate.
        return;
      }

      Iterable<SkyKey> directDeps;
      try {
        directDeps = walkableGraph.getDirectDeps(key);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      for (SkyKey dep : directDeps) {
        if (!(dep instanceof ActionLookupKey)) {
          // The subgraph of dependencies of ActionLookupKeys never has a non-ActionLookupKey
          // depending on an ActionLookupKey. So we can skip any non-ActionLookupKeys in the
          // traversal as an optimization.
          continue;
        }
        ActionLookupKey depKey = (ActionLookupKey) dep;
        if (shouldVisit(depKey, inRerun)) {
          exclusivePool.execute(
              new CheckForConflictsUnderKey(
                  depKey, futures, badActionMap, inRerun, strictConflictChecks));
        }
      }
      var finalValue = value;
      Callable<Void> goThroughActions =
          () ->
              actionRegistration(
                  finalValue,
                  threadSafeMutableActionGraph,
                  pathFragmentTrieRoot,
                  strictConflictChecks,
                  badActionMap);
      synchronized (freeForAllPool) {
        // Some other thread shut down the executor, exit now. This can happen in the case of an
        // analysis error.
        if (freeForAllPool.isShutdown()) {
          return;
        }
        // This should be fast, we're only submitting the task here.
        futures.add(freeForAllPool.submit(goThroughActions));
      }
    }
  }
}
