package io.vertx.howtos.hr;


import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.hibernate.reactive.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.persistence.Persistence;
import java.util.Map;


public class MainVerticle extends AbstractVerticle {

  private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

  private Stage.SessionFactory sessionFactory;  // <1>


  public static void main(String[] args) {

    long startTime = System.currentTimeMillis();

    logger.info("🚀 Starting a PostgreSQL container");

    PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:11-alpine")
      .withDatabaseName("postgres")
      .withUsername("postgres")
      .withPassword("vertx-in-action");

    postgreSQLContainer.start();

    long tcTime = System.currentTimeMillis();

    logger.info("🚀 Starting Vert.x");

    Vertx vertx = Vertx.vertx();

    DeploymentOptions options = new DeploymentOptions().setConfig(new JsonObject()
      .put("pgPort", postgreSQLContainer.getMappedPort(5432))); // <1>

    vertx.deployVerticle(MainVerticle::new, options).onComplete(ar -> {
      if (ar.succeeded()) {
        long vertxTime = System.currentTimeMillis();
        logger.info("✅ Deployment success");
        logger.info("💡 PostgreSQL container started in {}ms", (tcTime - startTime));
        logger.info("💡 Vert.x app started in {}ms", (vertxTime - tcTime));
      } else {
        logger.error("🔥 Deployment failure", ar.cause());
      }
    });
  }


  @Override
  public void start() {

    vertx.executeBlocking(
      promise -> {
        var pgPort = config().getInteger("pgPort", 5432);
        var props = Map.of("javax.persistence.jdbc.url", "jdbc:postgresql://localhost:" + pgPort + "/postgres");  // <1>
        sessionFactory = Persistence
          .createEntityManagerFactory("pg-demo", props)
          .unwrap(Stage.SessionFactory.class);
        promise.complete(sessionFactory);
      },
      res -> {
        logger.info("✅ Hibernate Reactive is ready");

        Router router = Router.router(vertx);

        BodyHandler bodyHandler = BodyHandler.create();
        router.post().handler(bodyHandler);

        router.get("/products").handler(this::listProducts);
        router.get("/products/:id").handler(this::getProduct);
        router.post("/products").handler(this::createProduct);

        vertx.createHttpServer()
          .requestHandler(router::handle)
          .listen(8080)
          .onSuccess(s -> logger.info("✅ HTTP server listening on port 8080"));
      }
    );
  }


  private void listProducts(RoutingContext ctx) {

    sessionFactory.withSession(session ->
      session
        .createQuery("from Product", Product.class)
        .getResultList().thenAcceptAsync(a -> ctx.end(new JsonArray(a).toString()))
    );
  }


  private void getProduct(RoutingContext ctx) {

    long id = Long.parseLong(ctx.pathParam("id"));
    sessionFactory.withSession(session -> session
      .find(Product.class, id)).thenAcceptAsync(p -> {
      if (p == null) {
        ctx.end("");
      } else {
        ctx.end(JsonObject.mapFrom(p).toString());
      }
    });
  }


  private void createProduct(RoutingContext ctx) {

    Product product = ctx.getBodyAsJson().mapTo(Product.class);
    sessionFactory.withSession(session -> session.
      persist(product).thenAcceptAsync(p -> ctx.end(JsonObject.mapFrom(p).toString())));
  }
}
