/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.cellar.features;

import org.apache.karaf.cellar.core.Configurations;
import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.control.SwitchStatus;
import org.apache.karaf.cellar.core.event.EventProducer;
import org.apache.karaf.cellar.core.event.EventType;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeatureEvent;
import org.apache.karaf.features.RepositoryEvent;
import org.osgi.service.cm.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local features listener.
 */
public class LocalFeaturesListener extends FeaturesSupport implements org.apache.karaf.features.FeaturesListener {

    private static final transient Logger LOGGER = LoggerFactory.getLogger(LocalFeaturesListener.class);

    private EventProducer eventProducer;

    @Override
    public void init() {
        super.init();
    }

    @Override
    public void destroy() {
        super.destroy();
    }

    /**
     * Callback method called when a local feature has changed.
     *
     * @param event the local feature event.
     */
    @Override
    public void featureEvent(FeatureEvent event) {

        if (!isEnabled()) {
            LOGGER.trace("CELLAR FEATURES: local listener is disabled");
            return;
        }

        // check if the producer is ON
        if (eventProducer.getSwitch().getStatus().equals(SwitchStatus.OFF)) {
            LOGGER.debug("CELLAR FEATURES: cluster event producer is OFF");
            return;
        }

        if (event != null) {
            Set<Group> groups = groupManager.listLocalGroups();

            if (groups != null && !groups.isEmpty()) {
                for (Group group : groups) {

                    Feature feature = event.getFeature();
                    String name = feature.getName();
                    String version = feature.getVersion();

                    if (isAllowed(group, Constants.CATEGORY, name, EventType.OUTBOUND)) {
                        FeatureEvent.EventType type = event.getType();

                        // update the features in the cluster group
                        Map<String, FeatureState> clusterFeatures = clusterManager.getMap(Constants.FEATURES_MAP + Configurations.SEPARATOR + group.getName());
                        FeatureState clusterFeatureState = new FeatureState();
                        clusterFeatureState.setName(event.getFeature().getName());
                        clusterFeatureState.setVersion(event.getFeature().getVersion());
                        if (FeatureEvent.EventType.FeatureInstalled.equals(event.getType())) {
                            clusterFeatureState.setInstalled(Boolean.TRUE);
                        } else {
                            clusterFeatureState.setInstalled(Boolean.FALSE);
                        }
                        clusterFeatures.put(event.getFeature().getName() + "/" + event.getFeature().getVersion(), clusterFeatureState);

                        // broadcast the cluster event
                        ClusterFeaturesEvent featureEvent = new ClusterFeaturesEvent(name, version, type);
                        featureEvent.setSourceGroup(group);
                        featureEvent.setSourceNode(clusterManager.getNode());
                        eventProducer.produce(featureEvent);
                    } else
                        LOGGER.debug("CELLAR FEATURES: feature {} is marked BLOCKED OUTBOUND for cluster group {}", name, group.getName());
                }
            }
        }
    }

    /**
     * Callback method called when a local features repository has changed.
     *
     * @param event the local repository event.
     */
    @Override
    public void repositoryEvent(RepositoryEvent event) {

        if (!isEnabled()) {
            LOGGER.trace("CELLAR FEATURES: local listener is disabled");
            return;
        }

        // check if the producer is ON
        if (eventProducer.getSwitch().getStatus().equals(SwitchStatus.OFF)) {
            LOGGER.debug("CELLAR FEATURES: cluster event producer is OFF");
            return;
        }

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
            if (event != null && event.getRepository() != null) {
                Set<Group> groups = groupManager.listLocalGroups();

                if (groups != null && !groups.isEmpty()) {
                    for (Group group : groups) {
                        ClusterRepositoryEvent repositoryEvent = new ClusterRepositoryEvent(event.getRepository().getURI().toString(), event.getType());
                        repositoryEvent.setSourceGroup(group);
                        repositoryEvent.setSourceNode(clusterManager.getNode());
                        RepositoryEvent.EventType type = event.getType();

                        List<String> clusterRepositories = clusterManager.getList(Constants.REPOSITORIES_LIST + Configurations.SEPARATOR + group.getName());

                        if (RepositoryEvent.EventType.RepositoryAdded.equals(type)) {
                            // update the repositories in the cluster group
                            if (!clusterRepositories.contains(event.getRepository().getURI().toString())) {
                                clusterRepositories.add(event.getRepository().getURI().toString());
                            }
                            // update the features in the cluster group
                            Map<String, FeatureState> clusterFeatures = clusterManager.getMap(Constants.FEATURES_MAP + Configurations.SEPARATOR + group.getName());
                            try {
                                for (Feature feature : event.getRepository().getFeatures()) {
                                    // check the feature in the cluster group
                                    FeatureState clusterFeatureState = new FeatureState();
                                    clusterFeatureState.setName(feature.getName());
                                    clusterFeatureState.setVersion(feature.getVersion());
                                    clusterFeatureState.setInstalled(Boolean.FALSE);
                                    clusterFeatures.put(feature.getName() + "/" + feature.getVersion(), clusterFeatureState);
                                }
                            } catch (Exception e) {
                                LOGGER.warn("CELLAR FEATURES: failed to update the cluster group", e);
                            }
                        } else {
                            // update the repositories in the cluster group
                            clusterRepositories.remove(event.getRepository().getURI().toString());
                            // update the features in the cluster group
                            Map<String, FeatureState> clusterFeatures = clusterManager.getMap(Constants.FEATURES_MAP + Configurations.SEPARATOR + group.getName());
                            try {
                                for (Feature feature : event.getRepository().getFeatures()) {
                                    clusterFeatures.remove(feature.getName() + "/" + feature.getVersion());
                                }
                            } catch (Exception e) {
                                LOGGER.warn("CELLAR FEATURES: failed to update the cluster group", e);
                            }
                        }

                        // broadcast the cluster event
                        eventProducer.produce(repositoryEvent);
                    }
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    /**
     * Check if the local node feature listener is enabled in the etc/org.apache.karaf.cellar.groups.cfg.
     *
     * @return true if enabled, false else.
     */
    private boolean isEnabled() {
        try {
            Configuration configuration = configurationAdmin.getConfiguration(Configurations.NODE, null);
            Dictionary<String, Object> properties = configuration.getProperties();
            if (properties != null) {
                String value = properties.get(Constants.CATEGORY + Configurations.SEPARATOR + Configurations.LISTENER).toString();
                return Boolean.parseBoolean(value);
            }
        } catch (Exception e) {
            LOGGER.warn("CELLAR FEATURE: can't check listener configuration", e);
        }
        return false;
    }

    public EventProducer getEventProducer() {
        return eventProducer;
    }

    public void setEventProducer(EventProducer eventProducer) {
        this.eventProducer = eventProducer;
    }

}
