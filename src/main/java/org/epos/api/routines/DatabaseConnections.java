package org.epos.api.routines;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import dao.EposDataModelDAO;
import org.epos.api.beans.Plugin;
import org.epos.api.utility.Utils;
import org.epos.eposdatamodel.User;
import org.epos.router_framework.RpcRouter;
import org.epos.router_framework.RpcRouterBuilder;
import org.epos.router_framework.domain.Actor;
import org.epos.router_framework.domain.BuiltInActorType;
import org.epos.router_framework.domain.Request;
import org.epos.router_framework.domain.RequestBuilder;
import org.epos.router_framework.domain.Response;
import org.epos.router_framework.types.ServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import usermanagementapis.UserGroupManagementAPI;

public class DatabaseConnections {
	private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseConnections.class);

	// distributionId -> list of relations with plugins
	private Map<String, List<Plugin.Relations>> plugins;

    public RpcRouter getRouter() {
        return router;
    }

    public void setRouter(RpcRouter router) {
        this.router = router;
    }

    private RpcRouter router;

	private int maxDbConnections = 18;
	private static DatabaseConnections connections;
	private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private static int currentErrors = 0;

	private DatabaseConnections() {
		try {
			router = RpcRouterBuilder.instance(Actor.getInstance(BuiltInActorType.CONVERTER))
					.addServiceSupport(ServiceType.METADATA, Actor.getInstance(BuiltInActorType.CONVERTER))
					.setNumberOfPublishers(1)
					.setNumberOfConsumers(1)
					.setRoutingKeyPrefix("resources")
					.build().get();
			router.init(
					System.getenv("BROKER_HOST"),
					System.getenv("BROKER_VHOST"),
					System.getenv("BROKER_USERNAME"),
					System.getenv("BROKER_PASSWORD"));
			LOGGER.info("[CONNECTION] Router initialized");
		} catch (Exception e) {
			LOGGER.error("[CONNECTION ERROR] Error while initializing router", e);
		}

		try {
			int connPoolMaxSize = Integer.parseInt(System.getenv("CONNECTION_POOL_MAX_SIZE"));
			this.maxDbConnections = Math.min(connPoolMaxSize, this.maxDbConnections);
		} catch (NumberFormatException e) {
			LOGGER.error("Error while parsing env variable CONNECTION_POOL_MAX_SIZE", e);
		}
	}

	// create a safe future handling possible null values and exceptions
	private <T> CompletableFuture<T> createSafeFuture(
			Supplier<T> supplier,
			T defaultValue,
			String operationName,
			ExecutorService executor) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				T result = supplier.get();
				if (result == null) {
					currentErrors++;
					return defaultValue;
				}
				return result;
			} catch (Exception e) {
				LOGGER.error("[CONNECTION ERROR] Error in {}", operationName, e);
				currentErrors++;
				return defaultValue;
			}
		}, executor);
	}

    public void syncDatabaseConnections() {

        // one thread for each query
        ExecutorService executor = Executors.newFixedThreadPool(maxDbConnections);

        CompletableFuture<Map<String, List<Plugin.Relations>>> tempPluginsFuture = createSafeFuture(
                () -> retrievePlugins(),
                new HashMap<>(),
                "retrievePlugins",
                executor);

        // join the futures together
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                tempPluginsFuture);

        // block until all done
        allFutures.join();

        // retrieve the results of the queries
        Map<String, List<Plugin.Relations>> tempPlugins = tempPluginsFuture.join();

        // convert the list to a map <id, object> for faster retrieval, with null safety

        lock.writeLock().lock();

        // hot-swap the temp variables
        try {
            plugins = tempPlugins;
        } finally {
            lock.writeLock().unlock();
        }

        // free the executor's resources (outside the lock to avoid contention)
        executor.shutdown();
        try {
            // Wait for tasks to complete (with timeout)
            if (!executor.awaitTermination(180, TimeUnit.SECONDS)) {
                LOGGER.warn("Executor did not terminate in time, forcing shutdown...");
                executor.shutdownNow();

                // Wait again after forcing
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.error("Executor did not terminate after shutdownNow");
                }
            }
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for executor termination", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (currentErrors >= maxDbConnections) {
            LOGGER.error("Too many errors while syncing the cache ({}), exiting...", currentErrors);
            System.exit(1);
        }
    }

	public static Map<String, User> retrieveUserMap() {
		return createSafeUserMap(UserGroupManagementAPI.retrieveAllUsers());
	}

	private static Map<String, User> createSafeUserMap(List<User> userList) {
		if (userList == null || userList.isEmpty()) {
			return new HashMap<>();
		}

		return userList.stream()
				.filter(user -> user != null && user.getAuthIdentifier() != null)
				.collect(Collectors.toMap(
						User::getAuthIdentifier,
						Function.identity(),
						(existing, replacement) -> {
							LOGGER.warn("[WARNING] Duplicate user auth identifier found, keeping existing: {}",
									existing.getAuthIdentifier());
							return existing;
						}));
	}

	public static DatabaseConnections getInstance() {
		lock.readLock().lock();
		try {
			if (connections == null) {
				connections = new DatabaseConnections();
			}
			return connections;
		} finally {
			lock.readLock().unlock();
		}
	}

	public Map<String, List<Plugin.Relations>> getPlugins() {
		return safeRead(plugins);
	}

	private <T> T safeRead(T value) {
		lock.readLock().lock();
		try {
			return value;
		} finally {
			lock.readLock().unlock();
		}
	}

	protected Response doRequest(ServiceType service, Map<String, Object> requestParams) {
		return this.doRequest(service, null, requestParams);
	}

	protected Response doRequest(ServiceType service, Actor nextComponentOverride, Map<String, Object> requestParams) {
		Request localRequest = RequestBuilder.instance(service, "get", "plugininfo")
				.addPayloadPlainText(Utils.gson.toJson(requestParams))
				.addHeaders(new HashMap<>())
				.build();

		Response response;
		if (nextComponentOverride != null) {
			response = router.makeRequest(localRequest, nextComponentOverride);
		} else {
			response = router.makeRequest(localRequest);
		}

		return response;
	}

	private Map<String, List<Plugin.Relations>> retrievePlugins() {
		var result = new HashMap<String, List<Plugin.Relations>>();
		try {
			Map<String, Object> params = new HashMap<>();
			params.put("plugins", "all");
			Response conversionResponse = doRequest(
					ServiceType.METADATA,
					Actor.getInstance(BuiltInActorType.CONVERTER),
					params);
			if (conversionResponse != null && conversionResponse.getPayloadAsPlainText().isPresent()) {
				var plugins = Arrays
						.stream(Utils.gson.fromJson(conversionResponse.getPayloadAsPlainText().get(), Plugin[].class))
						.collect(Collectors.toList());
				for (Plugin plugin : plugins) {
					result.putIfAbsent(plugin.getDistributionId(), plugin.getRelations());
				}
			}
		} catch (Exception e) {
			LOGGER.error("[CONNECTION ERROR] Error getting response from router", e);
		}
		return result;
	}
}
