/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.karaf.cellar.bundle;

import org.apache.karaf.cellar.core.Configurations;
import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.Synchronizer;
import org.apache.karaf.cellar.core.control.SwitchStatus;
import org.apache.karaf.cellar.core.event.EventProducer;
import org.apache.karaf.cellar.core.event.EventType;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleReference;
import org.osgi.service.cm.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BundleSynchronizer extends BundleSupport implements Synchronizer {

    private static final transient Logger LOGGER = LoggerFactory.getLogger(BundleSynchronizer.class);

    private EventProducer eventProducer;

    /**
     * Registration method
     */
    public void init() {
        Set<Group> groups = groupManager.listLocalGroups();
        if (groups != null && !groups.isEmpty()) {
            for (Group group : groups) {
                if (isSyncEnabled(group)) {
                    pull(group);
                    push(group);
                } else LOGGER.warn("CELLAR BUNDLE: sync is disabled for group {}", group.getName());
            }
        }
    }

    /**
     * Destruction method
     */
    public void destroy() {

    }

    /**
     * Get the bundle to install from the distributed map
     */
    public void pull(Group group) {
        if (group != null) {
            String groupName = group.getName();
            Map<String, BundleState> bundleDistributedMap = clusterManager.getMap(Constants.BUNDLE_MAP + Configurations.SEPARATOR + groupName);

            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
                for (Map.Entry<String, BundleState> entry : bundleDistributedMap.entrySet()) {
                    String id = entry.getKey();
                    BundleState state = entry.getValue();

                    String[] tokens = id.split("/");
                    String symbolicName = tokens[0];
                    String version = tokens[1];
                    if (tokens != null && tokens.length == 2) {
                        if (state != null) {
                            String bundleLocation = state.getLocation();
                            if (isAllowed(group, Constants.CATEGORY, bundleLocation, EventType.INBOUND)) {
                                try {
                                    if (state.getStatus() == BundleEvent.INSTALLED) {
                                        installBundleFromLocation(state.getLocation());
                                    } else if (state.getStatus() == BundleEvent.STARTED) {
                                        installBundleFromLocation(state.getLocation());
                                        startBundle(symbolicName, version);
                                    }
                                } catch (BundleException e) {
                                    LOGGER.error("CELLAR BUNDLE: failed to pull bundle {}", id, e);
                                }
                            } else LOGGER.warn("CELLAR BUNDLE: bundle {} is marked as BLOCKED INBOUND", bundleLocation);
                        }
                    }
                }
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }
    }

    /**
     * Publishes local bundle to the cluster.
     */
    public void push(Group group) {

        // check if the producer is ON
        if (eventProducer.getSwitch().getStatus().equals(SwitchStatus.OFF)) {
            LOGGER.warn("CELLAR BUNDLE: cluster event producer is OFF");
            return;
        }

        if (group != null) {
            String groupName = group.getName();
            Map<String, BundleState> distributedBundles = clusterManager.getMap(Constants.BUNDLE_MAP + Configurations.SEPARATOR + groupName);

            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
                Bundle[] bundles;
                BundleContext bundleContext = ((BundleReference) getClass().getClassLoader()).getBundle().getBundleContext();

                bundles = bundleContext.getBundles();
                for (Bundle bundle : bundles) {
                    String symbolicName = bundle.getSymbolicName();
                    String version = bundle.getVersion().toString();
                    String bundleLocation = bundle.getLocation();
                    int status = bundle.getState();
                    String id = symbolicName + "/" + version;

                    //Check if the pid is marked as local.
                    if (isAllowed(group, Constants.CATEGORY, bundleLocation, EventType.OUTBOUND)) {

                        BundleState bundleState = new BundleState();
                        bundleState.setLocation(bundleLocation);

                        if (status == Bundle.ACTIVE)
                            status = BundleEvent.STARTED;
                        if (status == Bundle.INSTALLED)
                            status = BundleEvent.INSTALLED;
                        if (status == Bundle.RESOLVED)
                            status = BundleEvent.RESOLVED;
                        if (status == Bundle.STARTING)
                            status = BundleEvent.STARTING;
                        if (status == Bundle.UNINSTALLED)
                            status = BundleEvent.UNINSTALLED;
                        if (status == Bundle.STOPPING)
                            status = BundleEvent.STARTED;

                        bundleState.setStatus(status);

                        BundleState existingState = distributedBundles.get(id);

                        if (existingState == null ||
                                !existingState.getLocation().equals(bundleState.getLocation()) ||
                                existingState.getStatus() != bundleState.getStatus()) {
                            // update the distributed map
                            distributedBundles.put(id, bundleState);

                            // broadcast the event
                            RemoteBundleEvent event = new RemoteBundleEvent(symbolicName, version, bundleLocation, status);
                            event.setSourceGroup(group);
                            eventProducer.produce(event);
                        }

                    } else LOGGER.warn("CELLAR BUNDLE: bundle {} is marked as BLOCKED OUTBOUND", bundleLocation);
                }
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }
    }

    @Override
    public Boolean isSyncEnabled(Group group) {
        Boolean result = Boolean.FALSE;
        String groupName = group.getName();

        try {
            Configuration configuration = configurationAdmin.getConfiguration(Configurations.GROUP);
            Dictionary<String, Object> properties = configuration.getProperties();
            if (properties != null) {
                String propertyKey = groupName + Configurations.SEPARATOR + Constants.CATEGORY + Configurations.SEPARATOR + Configurations.SYNC;
                String propertyValue = (String) properties.get(propertyKey);
                result = Boolean.parseBoolean(propertyValue);
            }
        } catch (IOException e) {
            LOGGER.error("CELLAR BUNDLE: error while checking if sync is enabled", e);
        }
        return result;
    }

    public EventProducer getEventProducer() {
        return eventProducer;
    }

    public void setEventProducer(EventProducer eventProducer) {
        this.eventProducer = eventProducer;
    }

}
