package com.cineisle.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.widget.TextView;
import android.widget.VideoView;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class RoomReceiveTest {
    private static final String TOKEN = "unit-test-token-with-at-least-32-characters";
    private HttpServer server;
    private ExecutorService executor;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @Before public void setUp() throws Exception {
        response.set(roomResponse("initial-message"));
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/rooms/TEST42", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            int code = status.get();
            byte[] body = (code == 200 ? response.get() : "{\"ok\":false,\"error\":\"CINEISLE_BAD_TOKEN\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, body.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        RuntimeEnvironment.getApplication().getSharedPreferences("cineisle", 0).edit().clear()
                .putString("serverUrl", "http://127.0.0.1:" + server.getAddress().getPort())
                .putString("token", TOKEN).putString("roomId", "TEST42")
                .putBoolean("contextAutoSync", false).commit();
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test public void savedRoomReceivesMessagesAndPollingResumesAfterBackground() throws Exception {
        launch();
        awaitText("chatLog", "initial-message");
        assertEquals("Bearer " + TOKEN, authorization.get());
        assertTrue(text("roomReceiveState").contains("已更新"));

        controller.pause().stop();
        int count = requests.get();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
        assertEquals("No room polling while stopped", count, requests.get());

        response.set(roomResponse("message-sent-while-away"));
        controller.restart().start().resume().visible();
        awaitText("chatLog", "message-sent-while-away");
    }

    @Test public void authorizationFailureIsVisibleAndManualRefreshRecovers() throws Exception {
        status.set(403);
        launch();
        awaitText("roomReceiveState", "HTTP 403");
        assertFalse("Status must not reveal the credential", text("roomReceiveState").contains(TOKEN));

        response.set(roomResponse("message-after-auth-recovery"));
        status.set(200);
        invoke("refreshRoomMessages");
        awaitText("chatLog", "message-after-auth-recovery");
        assertTrue(text("roomReceiveState").contains("已更新"));
    }

    @Test public void playerFailureDoesNotPreventNewMessagesFromRendering() throws Exception {
        launch();
        awaitText("chatLog", "initial-message");
        Field player = field("video");
        player.set(activity, new VideoView(activity) {
            @Override public int getDuration() { throw new IllegalStateException("player not ready"); }
        });
        response.set(roomResponse("message-despite-player-failure"));
        invoke("refreshRoomMessages");
        awaitText("chatLog", "message-despite-player-failure");
        assertTrue(text("roomReceiveState").contains("已更新"));
    }

    private void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
    }

    private static String roomResponse(String text) throws Exception {
        JSONObject message = new JSONObject().put("id", text).put("name", "Test sender")
                .put("text", text).put("at", "2026-01-01T00:00:00.000Z");
        JSONObject room = new JSONObject().put("id", "TEST42").put("title", "Test film")
                .put("messages", new JSONArray().put(message)).put("notes", new JSONArray())
                .put("members", new JSONArray()).put("duration", 0).put("paused", true)
                .put("currentTime", 0).put("context", new JSONObject());
        return new JSONObject().put("ok", true).put("room", room).toString();
    }

    private Field field(String name) throws Exception {
        Field value = MainActivity.class.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private String text(String name) throws Exception {
        return ((TextView) field(name).get(activity)).getText().toString();
    }

    private void invoke(String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }

    private void awaitText(String field, String expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (text(field).contains(expected)) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Expected " + expected + " in " + field + "; got " + text(field));
    }
}
