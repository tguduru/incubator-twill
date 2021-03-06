/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.twill.internal.container;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.apache.twill.api.Command;
import org.apache.twill.api.RunId;
import org.apache.twill.api.TwillRunnable;
import org.apache.twill.api.TwillRunnableSpecification;
import org.apache.twill.common.Threads;
import org.apache.twill.filesystem.Location;
import org.apache.twill.internal.BasicTwillContext;
import org.apache.twill.internal.ContainerInfo;
import org.apache.twill.internal.ContainerLiveNodeData;
import org.apache.twill.internal.state.Message;
import org.apache.twill.internal.utils.Instances;
import org.apache.twill.internal.yarn.AbstractYarnTwillService;
import org.apache.twill.zookeeper.ZKClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This class act as a yarn container and run a {@link org.apache.twill.api.TwillRunnable}.
 */
public final class TwillContainerService extends AbstractYarnTwillService {

  private static final Logger LOG = LoggerFactory.getLogger(TwillContainerService.class);

  private final TwillRunnableSpecification specification;
  private final ClassLoader classLoader;
  private final BasicTwillContext context;
  private final ContainerLiveNodeData containerLiveNodeData;
  private ExecutorService commandExecutor;
  private TwillRunnable runnable;

  public TwillContainerService(BasicTwillContext context, ContainerInfo containerInfo, ZKClient zkClient,
                               RunId runId, TwillRunnableSpecification specification, ClassLoader classLoader,
                               Location applicationLocation) {
    super(zkClient, runId, applicationLocation);

    this.specification = specification;
    this.classLoader = classLoader;
    this.containerLiveNodeData = createLiveNodeData(containerInfo);
    this.context = context;
  }

  private ContainerLiveNodeData createLiveNodeData(ContainerInfo containerInfo) {
    // if debugging is enabled, log the port and register it in service discovery.
    String debugPort = System.getProperty("twill.debug.port");
    if (debugPort != null) {
      LOG.info("JVM is listening for debugger on port {}", debugPort);
    }
    return new ContainerLiveNodeData(containerInfo.getId(),
                                     containerInfo.getHost().getCanonicalHostName(),
                                     debugPort);
  }

  @Override
  protected Object getLiveNodeData() {
    return containerLiveNodeData;
  }

  @Override
  public ListenableFuture<String> onReceived(final String messageId, final Message message) {
    LOG.debug("Message received: {} {}.", messageId, message);

    if (handleSecureStoreUpdate(message)) {
      return Futures.immediateFuture(messageId);
    }

    final SettableFuture<String> result = SettableFuture.create();
    Command command = message.getCommand();
    if (message.getType() == Message.Type.SYSTEM
      && "instances".equals(command.getCommand()) && command.getOptions().containsKey("count")) {
      context.setInstanceCount(Integer.parseInt(command.getOptions().get("count")));
    }

    commandExecutor.execute(new Runnable() {

      @Override
      public void run() {
        try {
          runnable.handleCommand(message.getCommand());
          result.set(messageId);
        } catch (Exception e) {
          result.setException(e);
        }
      }
    });
    return result;
  }

  @SuppressWarnings("unchecked")
  @Override
  protected void doStart() throws Exception {
    commandExecutor = Executors.newSingleThreadExecutor(
      Threads.createDaemonThreadFactory("runnable-command-executor"));

    Class<?> runnableClass = classLoader.loadClass(specification.getClassName());
    Preconditions.checkArgument(TwillRunnable.class.isAssignableFrom(runnableClass),
                                "Class %s is not instance of TwillRunnable.", specification.getClassName());

    runnable = Instances.newInstance((Class<TwillRunnable>) runnableClass);
    runnable.initialize(context);
  }

  @Override
  protected void doRun() throws Exception {
    runnable.run();
  }

  @Override
  protected void doStop() throws Exception {
    commandExecutor.shutdownNow();
    try {
      runnable.destroy();
    } catch (Throwable t) {
      // Just catch the exception, not propagate it since it's already in shutdown sequence and
      // we want all twill services properly shutdown.
      LOG.warn("Exception when calling runnable.destroy.", t);
    } finally {
      context.stop();
    }
  }

  @Override
  protected void triggerShutdown() {
    try {
      runnable.stop();
    } catch (Throwable t) {
      LOG.error("Exception when stopping runnable.", t);
    }
  }
}
