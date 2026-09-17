package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.AuraModule;
import io.aura.AuraWebRuntime;
import io.aura.ModuleContext;
import io.aura.contract.ModuleMetadata;
import io.aura.contract.RouteContribution;

final class TestApplications {
    private TestApplications() { }

    static AuraWebRuntime runtime() {
        return runtime(new AuraConfig());
    }

    static AuraWebRuntime runtime(AuraConfig config) {
        return (AuraWebRuntime) AuraApp.create(config).runtimeView();
    }

    static TestClient client(Router router) {
        return new TestClient(runtime(), router);
    }

    static TestClient client(AuraConfig config, Router router) {
        return new TestClient(runtime(config), router);
    }

    static AuraApp app(String moduleId, RouteContribution... routes) {
        return app(new AuraConfig(), moduleId, routes);
    }

    static AuraApp app(AuraConfig config, String moduleId, RouteContribution... routes) {
        return AuraApp.create(config).use(routes(moduleId, routes));
    }

    static AuraModule routes(String moduleId, RouteContribution... routes) {
        return new AuraModule() {
            @Override public ModuleMetadata descriptor() {
                return ModuleMetadata.of(moduleId, "1.0.0");
            }

            @Override public void install(ModuleContext context) {
                for (RouteContribution route : routes) context.route(route);
            }
        };
    }
}
