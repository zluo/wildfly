/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.clustering.web.undertow.sso;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.clustering.service.Builder;
import org.wildfly.extension.undertow.AbstractUndertowEventListener;
import org.wildfly.extension.undertow.Host;
import org.wildfly.extension.undertow.UndertowService;

import io.undertow.server.session.SessionListener;
import io.undertow.server.session.SessionManager;
import io.undertow.servlet.api.Deployment;

/**
 * Service providing a {@link SessionManagerRegistry} for a host.
 * @author Paul Ferraro
 */
public class SessionManagerRegistryBuilder extends AbstractUndertowEventListener implements Builder<SessionManagerRegistry>, Service<SessionManagerRegistry>, SessionManagerRegistry {

    private final ServiceName hostServiceName;
    private final InjectedValue<UndertowService> service = new InjectedValue<>();
    private final InjectedValue<Host> host = new InjectedValue<>();
    private final InjectedValue<SessionListener> listener = new InjectedValue<>();
    private final ConcurrentMap<String, SessionManager> managers = new ConcurrentHashMap<>();
    private final ServiceName listenerServiceName;

    public SessionManagerRegistryBuilder(ServiceName hostServiceName, ServiceName listenerServiceName) {
        this.hostServiceName = hostServiceName;
        this.listenerServiceName = listenerServiceName;
    }

    @Override
    public ServiceName getServiceName() {
        return this.hostServiceName.append("managers");
    }

    @Override
    public ServiceBuilder<SessionManagerRegistry> build(ServiceTarget target) {
        return target.addService(this.getServiceName(), this)
                .addDependency(UndertowService.UNDERTOW, UndertowService.class, this.service)
                .addDependency(this.hostServiceName, Host.class, this.host)
                .addDependency(this.listenerServiceName, SessionListener.class, this.listener)
                .setInitialMode(ServiceController.Mode.ON_DEMAND);
    }

    @Override
    public SessionManagerRegistry getValue() {
        return this;
    }

    @Override
    public void start(StartContext context) throws StartException {
        this.service.getValue().registerListener(this);
        this.host.getValue().getDeployments().forEach(deployment -> this.addDeployment(deployment));
    }

    @Override
    public void stop(StopContext context) {
        this.host.getValue().getDeployments().forEach(deployment -> this.removeDeployment(deployment));
        this.service.getValue().unregisterListener(this);
    }

    private void addDeployment(Deployment deployment) {
        SessionManager manager = deployment.getSessionManager();
        if (this.managers.putIfAbsent(deployment.getDeploymentInfo().getDeploymentName(), deployment.getSessionManager()) == null) {
            manager.registerSessionListener(this.listener.getValue());
        }
    }

    private void removeDeployment(Deployment deployment) {
        if (this.managers.remove(deployment.getDeploymentInfo().getDeploymentName()) != null) {
            deployment.getSessionManager().removeSessionListener(this.listener.getValue());
        }
    }

    @Override
    public void onDeploymentStart(Deployment deployment, Host host) {
        if (this.host.getValue().getName().equals(host.getName())) {
            this.addDeployment(deployment);
        }
    }

    @Override
    public void onDeploymentStop(Deployment deployment, Host host) {
        if (this.host.getValue().getName().equals(host.getName())) {
            this.removeDeployment(deployment);
        }
    }

    @Override
    public SessionManager getSessionManager(String deployment) {
        return this.managers.get(deployment);
    }
}
