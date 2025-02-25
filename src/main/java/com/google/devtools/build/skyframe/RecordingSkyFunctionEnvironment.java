// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** An environment that can observe the deps requested through getValue(s) calls. */
public final class RecordingSkyFunctionEnvironment implements Environment {

  private final Environment delegate;
  private final Consumer<SkyKey> skyKeyReceiver;
  private final Consumer<Iterable<SkyKey>> skyKeysReceiver;
  private final Consumer<Exception> exceptionReceiver;

  public RecordingSkyFunctionEnvironment(
      Environment delegate,
      Consumer<SkyKey> skyKeyReceiver,
      Consumer<Iterable<SkyKey>> skyKeysReceiver,
      Consumer<Exception> exceptionReceiver) {
    this.delegate = delegate;
    this.skyKeyReceiver = skyKeyReceiver;
    this.skyKeysReceiver = skyKeysReceiver;
    this.exceptionReceiver = exceptionReceiver;
  }

  private void recordDep(SkyKey key) {
    skyKeyReceiver.accept(key);
  }

  @SuppressWarnings("unchecked") // Cast Iterable<? extends SkyKey> to Iterable<SkyKey>.
  private void recordDeps(Iterable<? extends SkyKey> keys) {
    skyKeysReceiver.accept((Iterable<SkyKey>) keys);
  }

  private void noteException(Exception e) {
    exceptionReceiver.accept(e);
  }

  public Environment getDelegate() {
    return delegate;
  }

  @Nullable
  @Override
  public SkyValue getValue(SkyKey valueName) throws InterruptedException {
    recordDep(valueName);
    return delegate.getValue(valueName);
  }

  @Nullable
  @Override
  public <E extends Exception> SkyValue getValueOrThrow(SkyKey depKey, Class<E> exceptionClass)
      throws E, InterruptedException {
    recordDep(depKey);
    try {
      return delegate.getValueOrThrow(depKey, exceptionClass);
    } catch (Exception e) {
      noteException(e);
      throw e;
    }
  }

  @Nullable
  @Override
  public <E1 extends Exception, E2 extends Exception> SkyValue getValueOrThrow(
      SkyKey depKey, Class<E1> exceptionClass1, Class<E2> exceptionClass2)
      throws E1, E2, InterruptedException {
    recordDep(depKey);
    try {
      return delegate.getValueOrThrow(depKey, exceptionClass1, exceptionClass2);
    } catch (Exception e) {
      noteException(e);
      throw e;
    }
  }

  @Nullable
  @Override
  public <E1 extends Exception, E2 extends Exception, E3 extends Exception>
      SkyValue getValueOrThrow(
          SkyKey depKey,
          Class<E1> exceptionClass1,
          Class<E2> exceptionClass2,
          Class<E3> exceptionClass3)
          throws E1, E2, E3, InterruptedException {
    recordDep(depKey);
    try {
      return delegate.getValueOrThrow(depKey, exceptionClass1, exceptionClass2, exceptionClass3);
    } catch (Exception e) {
      noteException(e);
      throw e;
    }
  }

  @Nullable
  @Override
  public <E1 extends Exception, E2 extends Exception, E3 extends Exception, E4 extends Exception>
      SkyValue getValueOrThrow(
          SkyKey depKey,
          Class<E1> exceptionClass1,
          Class<E2> exceptionClass2,
          Class<E3> exceptionClass3,
          Class<E4> exceptionClass4)
          throws E1, E2, E3, E4, InterruptedException {
    recordDep(depKey);
    try {
      return delegate.getValueOrThrow(
          depKey, exceptionClass1, exceptionClass2, exceptionClass3, exceptionClass4);
    } catch (Exception e) {
      noteException(e);
      throw e;
    }
  }

  @Override
  public boolean valuesMissing() {
    return delegate.valuesMissing();
  }

  @Override
  public SkyframeLookupResult getValuesAndExceptions(Iterable<? extends SkyKey> depKeys)
      throws InterruptedException {
    recordDeps(depKeys);
    try {
      return delegate.getValuesAndExceptions(depKeys);
    } catch (Exception e) {
      noteException(e);
      throw e;
    }
  }

  @Override
  public ExtendedEventHandler getListener() {
    return delegate.getListener();
  }

  @Override
  public boolean inErrorBubblingForSkyFunctionsThatCanFullyRecoverFromErrors() {
    return delegate.inErrorBubblingForSkyFunctionsThatCanFullyRecoverFromErrors();
  }

  @Nullable
  @Override
  public GroupedDeps getTemporaryDirectDeps() {
    return delegate.getTemporaryDirectDeps();
  }

  @Override
  public void injectVersionForNonHermeticFunction(Version version) {
    delegate.injectVersionForNonHermeticFunction(version);
  }

  @Override
  public void registerDependencies(Iterable<SkyKey> keys) {
    delegate.registerDependencies(keys);
  }

  @Override
  public void dependOnFuture(ListenableFuture<?> future) {
    delegate.dependOnFuture(future);
  }

  @Override
  public boolean resetPermitted() {
    return delegate.resetPermitted();
  }

  @Override
  public SkyframeLookupResult getLookupHandleForPreviouslyRequestedDeps() {
    return delegate.getLookupHandleForPreviouslyRequestedDeps();
  }

  @Override
  public <T extends SkyKeyComputeState> T getState(Supplier<T> stateSupplier) {
    return delegate.getState(stateSupplier);
  }

  @Override
  @Nullable
  public Version getMaxTransitiveSourceVersionSoFar() {
    return delegate.getMaxTransitiveSourceVersionSoFar();
  }
}
