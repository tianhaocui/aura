# Composed Aura Example

This example is the smallest recommended Aura 1.0 composition:

```java
AuraApp.create()
    .use(WorkflowModule.create())
    .use(new ComposedApp.OrderModule())
    .start();
```

`OrderModule` contributes a route and a workflow while declaring its workflow dependency. `ComposedAppTest` verifies both contributions through `TestClient` without opening a port.
