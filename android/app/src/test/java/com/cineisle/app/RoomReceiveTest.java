package com.cineisle.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
    private ServerSocket server;
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
        server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        executor.execute(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    executor.execute(() -> respond(client));
                } catch (java.io.IOException closed) {
                    break;
                }
            }
        });
        RuntimeEnvironment.getApplication().getSharedPreferences("cineisle", 0).edit().clear()
                .putString("serverUrl", "http://127.0.0.1:" + server.getLocalPort())
                .putString("token", TOKEN).putString("roomId", "TEST42")
                .putBoolean("contextAutoSync", false).commit();
    }

    @After public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        if (server != null) server.close();
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

    private void respond(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(3000);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = input.readLine();
            String header;
            while ((header = input.readLine()) != null && !header.isEmpty()) {
                if (header.regionMatches(true, 0, "Authorization:", 0, 14)) {
                    authorization.set(header.substring(14).trim());
                }
            }
            requests.incrementAndGet();
            int code = "GET /api/rooms/TEST42 HTTP/1.1".equals(requestLine) ? status.get() : 404;
            byte[] body = (code == 200 ? response.get() : "{\"ok\":false,\"error\":\"CINEISLE_BAD_TOKEN\"}")
                    .getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + code + " Test\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(body);
            output.flush();
        } catch (java.io.IOException closed) {
            // Tests close the listener and sockets during cleanup.
        }
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
