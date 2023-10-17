package com.storedobject.client;

import com.storedobject.common.JSON;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Client for the SO Platform Connector.
 *
 * @author Syam
 */
public class Client {

    private CompletableFuture<WebSocket> connecting;
    private WebSocket socket;
    private final int deviceWidth;
    private final int deviceHeight;
    private String username = "";
    private String password = "", session = "";
    private final List<String> responses = new ArrayList<>();
    private static final String NOT_CONNECTED = "Not connected";
    private volatile BufferedStream currentBinary;

    /**
     * Constructor that defines a secured connection.
     *
     * @param host Host where the SO is hosted.
     * @param application Name of the application (typically, the database name).
     */
    public Client(String host, String application) {
        this(host, application,true);
    }

    /**
     * Constructor that defines a secured connection.
     *
     * @param host Host where the SO is hosted.
     * @param application Name of the application (typically, the database name).
     * @param secured Whether secured connection (TLS encryption) is required or not.
     */
    public Client(String host, String application, boolean secured) {
        this(host, application, 0, 0, secured);
    }

    /**
     * Constructor that defines a secured connection.
     *
     * @param host Host where the SO is hosted.
     * @param application Name of the application (typically, the database name).
     * @param deviceWidth Device width (applicable if you are connecting from a device that has a view-width).
     * @param deviceHeight Device height (applicable if you are connecting from a device that has a view-height).
     */
    public Client(String host, String application, int deviceWidth, int deviceHeight) {
        this(host, application, deviceWidth, deviceHeight, true);
    }

    /**
     * Constructor.
     *
     * @param host Host where the SO is hosted.
     * @param application Name of the application (typically, the database name).
     * @param deviceWidth Device width (applicable if you are connecting from a device that has a view-width).
     * @param deviceHeight Device height (applicable if you are connecting from a device that has a view-height).
     * @param secured Whether secured connection (TLS encryption) is required or not.
     */
    public Client(String host, String application, int deviceWidth, int deviceHeight, boolean secured) {
        this.deviceWidth = deviceWidth <= 1 ? 1024 : deviceWidth;
        this.deviceHeight = deviceHeight <= 1 ? 768 : deviceHeight;
        connecting = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws" + (secured ? "s" : "") + "://" + host + "/" + application + "/CONNECTORWS"),
                        new Listener()).whenCompleteAsync((socket, error) -> {
                    if(error == null) {
                        this.socket = socket;
                    }
                    this.connecting = null;
                });
    }

    /**
     * Login method. This is the first method to be called.
     *
     * @param username Username.
     * @param password Password.
     * @return An empty string is returned if the process is successful. Otherwise, an error message is returned.
     */
    public String login(String username, String password) {
        if(!this.username.isEmpty()) {
            return "Already logged in";
        }
        if (username == null || username.isBlank()) {
            return "Username can't be empty";
        }
        Map<String , Object> map = new HashMap<>();
        map.put("command", "login");
        map.put("user", username);
        map.put("password", password == null ? "" : password);
        map.put("version", 1);
        map.put("deviceWidth", deviceWidth);
        map.put("deviceHeight", deviceHeight);
        session = "";
        JSON json = post(map);
        switch (json.getString("status")) {
            case "OK" -> {
                this.username = username;
                this.password = password;
                this.session = json.getString("session");
                return "";
            }
            case "ERROR" -> {
                return json.getString("message");
            }
        }
        return "Protocol error";
    }

    /**
     * Logout method. This should be invoked if the {@link Client} is no more required.
     */
    public void logout() {
        command("logout", new HashMap<>());
        session = password = username = "";
        socket.sendClose(0, "Logged out");
        socket.abort();
    }

    /**
     * Change password.
     *
     * @param currentPassword Current password.
     * @param newPassword New password.
     * @return An empty string is returned if the password is changed successfully. Otherwise, an error message is
     * returned.
     */
    public String changePassword(String currentPassword, String newPassword) {
        if(this.username.isEmpty()) {
            return "Not logged in";
        }
        if(!password.equals(currentPassword)) {
            return "Current password is incorrect";
        }
        Map<String , Object> map = new HashMap<>();
        map.put("oldPassword", password);
        map.put("password", newPassword == null ? "" : newPassword);
        JSON json = command("changePassword", map);
        switch (json.getString("status")) {
            case "OK" -> {
                this.password = newPassword;
                return "";
            }
            case "ERROR" -> {
                return json.getString("message");
            }
        }
        return "Protocol error";
    }

    /**
     * Send a command and receive a response.
     * <p>
     *   The [attributes] should contain a map of the parameters. Please refer to the
     *   <a href="https://github.com/syampillai/SOTraining/wiki/8900.-SO-Connector-API">SO Connector</a> documentation
     *   for parameter details. Please note that [command] is passed as the first parameter and thus, it need
     *   not be specified in the [attributes]. Also, "session" is not required because [Client] will
     *   automatically add that.
     * </p>
     * @param command Command.
     * @param attributes Attributes.
     * @return Response as a JSON instance is returned.
     */
    public JSON command(String command, Map<String, Object> attributes) {
        return command(command, attributes, false);
    }

    /**
     * Send a command and receive a response.
     * <p>
     *   The [attributes] should contain a map of the parameters. Please refer to the
     *   <a href="https://github.com/syampillai/SOTraining/wiki/8900.-SO-Connector-API">SO Connector</a> documentation
     *   for parameter details. Please note that [command] is passed as the first parameter and thus, it need
     *   not be specified in the [attributes]. Also, "session" is not required because [Client] will
     *   automatically add that. If the optional [preserveServerState] value is true,
     *   the "continue" attribute will be set to preserve the server state
     *   (See <a href="https://github.com/syampillai/SOTraining/wiki/8900.-SO-Connector-API#persisting-state-in-connector-logic">documentation</a>).
     * </p>
     * @param command Command.
     * @param attributes Attributes.
     * @param preserveServerState Whether preserve the server state or not.
     * @return Response as a JSON instance is returned.
     */
    public JSON command(String command, Map<String, Object> attributes, boolean preserveServerState) {
        return command(command, attributes, true, preserveServerState);
    }

    private JSON command(String command, Map<String, Object> attributes, boolean checkCommand, boolean preserveServerState) {
        if (username.isEmpty() || session.isEmpty()) {
            return error("Not logged in");
        }
        if(socket == null) {
            return error("Not connected");
        }
        if(checkCommand) {
            switch (command) {
                case "file", "stream":
                    return error("Invalid command");
            }
        }
        attributes.put("session", session);
        attributes.put("command", command);
        if (preserveServerState) {
            attributes.put("continue", true);
        }
        JSON r = post(attributes);
        if ("LOGIN".equals(r.getString("status"))) {
            session = r.getString("session");
            var u = username;
            username = "";
            var status = login(u, password);
            if (!status.isEmpty()) {
                return  error("Can't re-login. Reason: " + status);
            }
            return command(command, attributes, false);
        }
        return r;
    }

    private JSON error(String error) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", "ERROR");
        map.put("message", error);
        return new JSON(map);
    }

    private synchronized JSON post(Map<String, Object> map) {
        while (socket == null) {
            if(connecting == null) {
                return error(NOT_CONNECTED);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }
        if(socket.isOutputClosed()) {
            return error("Connection closed");
        }
        socket.sendText(new JSON(map).toString(), true);
        socket.request(1);
        while (true) {
            synchronized (responses) {
                if(!responses.isEmpty()) {
                    try {
                        return new JSON(responses.remove(0));
                    } catch (IOException e) {
                        return error("Invalid response");
                    }
                }
            }
            if(socket == null) {
                return error(NOT_CONNECTED);
            }
            if(socket.isInputClosed()) {
                return error("Connection closed");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Retrieve stream of data for the [name].
     * <p>You should have got the [name] from a previous request.</p>
     *
     * @param name Name of the stream. (Mostly as a stringified Id).
     * @return An instance of {@link  Data}. This contains an {@link InputStream} containing binary data if
     * the {@link Data#error()} is <code>null</code>. {@link Data#mimeType()} provides the content-type.
     */
    public Data  stream(String name) {
        return _stream("stream", name);
    }

    /**
     * Retrieve stream of data for the give file [name].
     * <p>You should have got the [name] from a previous request or it could be the name of a file in the SO platform.</p>
     *
     * @param name Name of the stream.
     * @return An instance of {@link  Data}. This contains an {@link InputStream} containing binary data if
     * the {@link Data#error()} is <code>null</code>. {@link Data#mimeType()} provides the content-type.
     */
    public Data file(String name) {
        return _stream("file", name);
    }

    private synchronized Data _stream(String command, String name) {
        Map<String, Object> map = new HashMap<>();
        map.put(command, name);
        while (currentBinary != null) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }
        currentBinary = new BufferedStream();
        InputStream in = currentBinary;
        var r = command(command, map, false, false);
        if("ERROR".equals(r.getString("status"))) {
            currentBinary = null;
            return new Data(null, null, r.getString("message"));
        }
        socket.request(1);
        return new Data(in, r.getString("type"), null);
    }

    /**
     * Structure to wrap an {@link InputStream} and its content-type. If the stream is absent (i.e., <code>null</code>),
     * the {@link Data#error()} provides the real error message.
     *
     * @param stream An instance of an {@link InputStream}. Please make sure that the stream closed once data is read
     *               or abandoned.
     * @param mimeType Content-type.
     * @param error Error if any.
     */
    public record Data(InputStream stream, String mimeType, String error) {
    }

    private class Listener implements WebSocket.Listener {

        private StringBuilder text = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if(last) {
                String s;
                if(!text.isEmpty()) {
                    text.append(data);
                    s = text.toString();
                    text = new StringBuilder();
                } else {
                    s = data.toString();
                }
                synchronized (responses) {
                    responses.add(s);
                }
            } else {
                text.append(data);
                if(socket != null) {
                    socket.request(1);
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if(currentBinary != null) {
                synchronized (currentBinary.buffers) {
                    currentBinary.buffers.add(data);
                    if(last) {
                        currentBinary.completed = true;
                        currentBinary = null;
                    } else if (socket != null) {
                        socket.request(1);
                    }
                }
            } else {
                if(socket != null) {
                    socket.request(1);
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            Client.this.socket = null;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            Client.this.socket = null;
        }
    }

    private class BufferedStream extends InputStream {

        final List<ByteBuffer> buffers = new ArrayList<>();
        private ByteBuffer buffer;
        volatile boolean completed = false;

        private void buf() {
            while (true) {
                synchronized (buffers) {
                    if(!buffers.isEmpty()) {
                        buffer = buffers.remove(0);
                        if(buffer.hasRemaining()) {
                            return;
                        }
                        buffer = null;
                        continue;
                    }
                }
                if(completed || socket == null) {
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
        }

        @Override
        public int read() {
            if(buffer == null) {
                buf();
            }
            if(buffer == null) {
                return -1;
            }
            int r = buffer.get();
            if(!buffer.hasRemaining()) {
                buffer = null;
            }
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            if(len <= 0) {
                return 0;
            }
            if(buffer == null) {
                buf();
            }
            if(buffer == null) {
                return -1;
            }
            if(len > buffer.remaining()) {
                len = buffer.remaining();
            }
            buffer.get(buffer.position(), b, off, len);
            buffer.position(buffer.position() + len);
            if(!buffer.hasRemaining()) {
                buffer = null;
            }
            return len;
        }

        @Override
        public void close() {
            completed = true;
            buffer = null;
            buffers.clear();
        }
    }
}
