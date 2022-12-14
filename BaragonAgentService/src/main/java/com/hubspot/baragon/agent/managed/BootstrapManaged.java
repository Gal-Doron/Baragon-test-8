package com.hubspot.baragon.agent.managed;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.hubspot.baragon.agent.BaragonAgentServiceModule;
import com.hubspot.baragon.agent.config.BaragonAgentConfiguration;
import com.hubspot.baragon.agent.healthcheck.ConfigChecker;
import com.hubspot.baragon.agent.healthcheck.InternalStateChecker;
import com.hubspot.baragon.agent.lbs.LocalLbAdapter;
import com.hubspot.baragon.agent.listeners.DirectoryChangesListener;
import com.hubspot.baragon.agent.listeners.ResyncListener;
import com.hubspot.baragon.agent.workers.AgentHeartbeatWorker;
import com.hubspot.baragon.data.BaragonKnownAgentsDatastore;
import com.hubspot.baragon.data.BaragonLoadBalancerDatastore;
import com.hubspot.baragon.models.BaragonAgentMetadata;
import com.hubspot.baragon.models.BaragonAgentState;
import com.hubspot.baragon.models.BaragonKnownAgentMetadata;

import io.dropwizard.lifecycle.Managed;

public class BootstrapManaged implements Managed {
  private static final Logger LOG = LoggerFactory.getLogger(BootstrapManaged.class);

  private final BaragonAgentConfiguration configuration;
  private final BaragonLoadBalancerDatastore loadBalancerDatastore;
  private final LeaderLatch leaderLatch;
  private final BaragonKnownAgentsDatastore knownAgentsDatastore;
  private final BaragonAgentMetadata baragonAgentMetadata;
  private final ScheduledExecutorService executorService;
  private final AgentHeartbeatWorker agentHeartbeatWorker;
  private final LifecycleHelper lifecycleHelper;
  private final CuratorFramework curatorFramework;
  private final ResyncListener resyncListener;
  private final ConfigChecker configChecker;
  private final InternalStateChecker internalStateChecker;
  private final DirectoryChangesListener directoryChangesListener;
  private final LocalLbAdapter lbAdapter;
  private final AtomicReference<BaragonAgentState> agentState;

  private ScheduledFuture<?> requestWorkerFuture = null;
  private ScheduledFuture<?> configCheckerFuture = null;
  private ScheduledFuture<?> stateCheckerFuture = null;

  @Inject
  public BootstrapManaged(BaragonKnownAgentsDatastore knownAgentsDatastore,
                          BaragonLoadBalancerDatastore loadBalancerDatastore,
                          BaragonAgentConfiguration configuration,
                          AgentHeartbeatWorker agentHeartbeatWorker,
                          BaragonAgentMetadata baragonAgentMetadata,
                          LifecycleHelper lifecycleHelper,
                          CuratorFramework curatorFramework,
                          ResyncListener resyncListener,
                          ConfigChecker configChecker,
                          AtomicReference<BaragonAgentState> agentState,
                          InternalStateChecker internalStateChecker,
                          DirectoryChangesListener directoryChangesListener,
                          LocalLbAdapter lbAdapter,
                          @Named(BaragonAgentServiceModule.AGENT_SCHEDULED_EXECUTOR) ScheduledExecutorService executorService,
                          @Named(BaragonAgentServiceModule.AGENT_LEADER_LATCH) LeaderLatch leaderLatch) {
    this.configuration = configuration;
    this.leaderLatch = leaderLatch;
    this.curatorFramework = curatorFramework;
    this.resyncListener = resyncListener;
    this.knownAgentsDatastore = knownAgentsDatastore;
    this.loadBalancerDatastore = loadBalancerDatastore;
    this.baragonAgentMetadata = baragonAgentMetadata;
    this.executorService = executorService;
    this.agentHeartbeatWorker = agentHeartbeatWorker;
    this.lifecycleHelper = lifecycleHelper;
    this.configChecker = configChecker;
    this.internalStateChecker = internalStateChecker;
    this.directoryChangesListener = directoryChangesListener;
    this.lbAdapter = lbAdapter;
    this.agentState = agentState;
  }



  @Override
  public void start() throws Exception {
    LOG.info("Updating watched files");
    directoryChangesListener.start();

    LOG.info("Applying current configs...");
    lifecycleHelper.applyCurrentConfigs();

    if (configuration.isVisibleToBaragonService()) {
      LOG.info("Starting leader latch...");
      leaderLatch.start();

      if (configuration.isRegisterOnStartup()) {
        LOG.info("Notifying BaragonService...");
        lifecycleHelper.notifyService("startup");
      }

      LOG.info("Updating BaragonGroup information...");
      loadBalancerDatastore.updateGroupInfo(configuration.getLoadBalancerConfiguration().getName(), configuration.getLoadBalancerConfiguration().getDefaultDomain(), configuration.getLoadBalancerConfiguration().getDomains(), configuration.getLoadBalancerConfiguration().getDomainAliases(), configuration.getLoadBalancerConfiguration().getMinHealthyAgents());

      LOG.info("Adding to known-agents...");
      knownAgentsDatastore.addKnownAgent(configuration.getLoadBalancerConfiguration().getName(), BaragonKnownAgentMetadata.fromAgentMetadata(baragonAgentMetadata, System.currentTimeMillis()));

      LOG.info("Starting agent heartbeat...");
      requestWorkerFuture = executorService.scheduleAtFixedRate(agentHeartbeatWorker, 0, configuration.getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);

      LOG.info("Adding resync listener");
      curatorFramework.getConnectionStateListenable().addListener(resyncListener);
    }

    LOG.info("Starting config checker");
    configCheckerFuture = executorService.scheduleAtFixedRate(configChecker, 0, configuration.getConfigCheckIntervalSecs(), TimeUnit.SECONDS);

    if (configuration.isEnablePollingStateValidation()) {
      LOG.info("Starting state reconciliation checker");
      stateCheckerFuture = executorService.scheduleAtFixedRate(internalStateChecker, configuration.getStateCheckIntervalSecs(), configuration.getStateCheckIntervalSecs(), TimeUnit.SECONDS);
    }

    lifecycleHelper.writeStateFileIfConfigured();

    if (configuration.getLoadBalancerConfiguration().getLogRotateCommand().isPresent()) {
      executorService.scheduleAtFixedRate(lbAdapter::triggerLogrotate, configuration.getLoadBalancerConfiguration().getRotateIntervalMillis(), configuration.getLoadBalancerConfiguration().getRotateIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    agentState.set(BaragonAgentState.ACCEPTING);
  }

  @Override
  public void stop() throws Exception {
    lifecycleHelper.shutdown();
    if (requestWorkerFuture != null) {
      requestWorkerFuture.cancel(true);
    }
    if (configCheckerFuture != null) {
      configCheckerFuture.cancel(true);
    }
    if (stateCheckerFuture != null) {
      stateCheckerFuture.cancel(true);
    }
    directoryChangesListener.stop();
  }
}
