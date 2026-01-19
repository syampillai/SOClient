package com.storedobject.client;

import com.storedobject.common.Fault;
import com.storedobject.common.IO;
import com.storedobject.common.JSON;
import com.storedobject.common.SOException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Client for the SO Platform Connector.
 *
 * @author Syam
 */
@SuppressWarnings("BusyWait")
public class Client {

    private final URI uri;
    private CountDownLatch connectionLatch, textLatch, binaryLatch, binaryFragmentLatch;
    private WebSocket socket;
    private final int deviceWidth;
    private final int deviceHeight;
    private String username = "";
    private String password = "", session = "";
    private final String apiKey;
    private Throwable error = null;
    private final List<String> responses = new ArrayList<>();
    private int apiVersion = 1;
    /**
     * Error message when not connected.
     */
    public static final String NOT_CONNECTED = "Not connected";
    private volatile BufferedStream currentBinary;
    private final Timer pingTimer;
    private long lastCommandAt = 0;
    private String otpEmail = "";

    /**
     * Constructor that defines a secured connection.
     *
     * @param host        Host where the SO is hosted.
     * @param application Name of the application (typically, the database name).
     */
    public Client(String host, String application) {
        this(host, application, true);
    }

    /**
     * Constructor that defines a secured connection.
     *
     * @param host        Host where the SO is hosted.
     * @param application Name of the application (typically, the database name).
     * @param apiKey      API key (if any).
     */
    public Client(String host, String application, String apiKey) {
        this(host, application, apiKey, true);
    }

    /**
     * Constructor that defines a secured connection.
     *
     * @param host        Host where the SO is hosted.
     * @param application Name of the application (typically, the database name).
     * @param secured     Whether a secured connection (TLS encryption) is required or not.
     */
    public Client(String host, String application, boolean secured) {
        this(host, application, 0, 0, secured);
    }

    /**
     * Constructor that defines a secured connection.
     *
     * @param host        Host where the SO is hosted.
     * @param application Name of the application (typically, the database name).
     * @param apiKey      API key (if any).
     * @param secured     Whether a secured connection (TLS encryption) is required or not.
     */
    public Client(String host, String application, String apiKey, boolean secured) {
        this(host, application, apiKey, 0, 0, secured);
    }

    /**
     * Constructor that defines a secured connection.
     *
     * @param host         Host where the SO is hosted.
     * @param application  Name of the application (typically, the database name).
     * @param deviceWidth  Device width (applicable if you are connecting from a device that has a view-width).
     * @param deviceHeight Device height (applicable if you are connecting from a device that has a view-height).
     */
    public Client(String host, String application, int deviceWidth, int deviceHeight) {
        this(host, application, deviceWidth, deviceHeight, true);
    }

    /**
     * Constructor that defines a secured connection.
     *
     * @param host         Host where the SO is hosted.
     * @param application  Name of the application (typically, the database name).
     * @param apiKey       API key (if any).
     * @param deviceWidth  Device width (applicable if you are connecting from a device that has a view-width).
     * @param deviceHeight Device height (applicable if you are connecting from a device that has a view-height).
     */
    public Client(String host, String application, String apiKey, int deviceWidth, int deviceHeight) {
        this(host, application, apiKey, deviceWidth, deviceHeight, true);
    }

    /**
     * Constructor.
     *
     * @param host         Host where the SO is hosted.
     * @param application  Name of the application (typically, the database name).
     * @param deviceWidth  Device width (applicable if you are connecting from a device that has a view-width).
     * @param deviceHeight Device height (applicable if you are connecting from a device that has a view-height).
     * @param secured      Whether a secured connection (TLS encryption) is required or not.
     */
    public Client(String host, String application, int deviceWidth, int deviceHeight, boolean secured) {
        this(host, application, null, deviceWidth, deviceHeight, secured);
    }

    /**
     * Constructor.
     *
     * @param host         Host where the SO is hosted.
     * @param application  Name of the application (typically, the database name).
     * @param apiKey       API key (if any).
     * @param deviceWidth  Device width (applicable if you are connecting from a device that has a view-width).
     * @param deviceHeight Device height (applicable if you are connecting from a device that has a view-height).
     * @param secured      Whether a secured connection (TLS encryption) is required or not.
     */
    public Client(String host, String application, String apiKey, int deviceWidth, int deviceHeight, boolean secured) {
        this.apiKey = apiKey != null && !apiKey.isBlank() ? apiKey : null;
        this.deviceWidth = deviceWidth <= 1 ? 1024 : deviceWidth;
        this.deviceHeight = deviceHeight <= 1 ? 768 : deviceHeight;
        this.uri = URI.create("ws" + (secured ? "s" : "") + "://" + host + "/" + application + "/CONNECTORWS");
        reconnect();
        pingTimer = new Timer();
        pingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                ping();
            }
        }, 0, 29000);
    }

    private void ping() {
        if (socket != null && (System.currentTimeMillis() - lastCommandAt) >= 29000) {
            socket.sendPing(ByteBuffer.wrap(new byte[]{1}));
            command("ping", new HashMap<>());
        }
    }

    /**
     * Reconnect the client.
     * <p>Note: This will reset everything. However, it knows how to re-log in to the server if required.</p>
     */
    public void reconnect() {
        if (connectionLatch != null) { // Connection in progress
            return;
        }
        WebSocket ws = this.socket;
        this.socket = null;
        responses.clear();
        if (currentBinary != null) {
            currentBinary.close();
            currentBinary = null;
        }
        error = null;
        if (ws != null) {
            ws.sendClose(102, "Reconnecting");
        }
        connectionLatch = new CountDownLatch(1);
        var builder = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofMinutes(10));
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        builder.buildAsync(uri, new Listener()).whenCompleteAsync((socket, error) -> {
            if (error == null) {
                this.socket = socket;
            } else {
                this.error = error;
            }
            connectionLatch.countDown();
            connectionLatch = null;
        });
    }

    public void setApiVersion(int apiVersion) {
        this.apiVersion = apiVersion;
    }

    /**
     * Get the current error, if any.
     *
     * @return Error or null if in any error state.
     */
    public Throwable getError() {
        if (connectionLatch != null) {
            try {
                connectionLatch.await();
            } catch (InterruptedException ignored) {
            }
        }
        return error;
    }


    /**
     * Login method. One of the login methods should be called first. This method is used when an API key is used to
     * connect.
     *
     * @param clientID Client ID.
     * @return An empty string is returned if the process is successful. Otherwise, an error message is returned.
     */
    public String login(String clientID) {
       return login(clientID, null);
    }

    /**
     * Login method. One of the login methods should be called first.
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
        if(apiKey != null && password != null) {
            password = null;
        }
        Map<String , Object> map = new HashMap<>();
        map.put("command", "login");
        map.put("user", username);
        if(password != null) {
            map.put("password", password);
        }
        map.put("version", apiVersion);
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

    public String login(int emailOTP, int mobileOTP) {
        if(!this.username.isEmpty()) {
            return "Already logged in";
        }
        if (otpEmail.isEmpty() || session.isEmpty()) {
            return "OTP was not generated";
        }
        Map<String , Object> map = new HashMap<>();
        map.put("command", "otp");
        map.put("action", "login");
        map.put("session", session);
        map.put("continue", true);
        map.put("emailOTP", emailOTP);
        map.put("mobileOTP", mobileOTP);
        map.put("version", apiVersion);
        map.put("deviceWidth", deviceWidth);
        map.put("deviceHeight", deviceHeight);
        JSON json = post(map);
        switch (json.getString("status")) {
            case "OK" -> {
                this.username = otpEmail;
                this.password = json.getString("secret");
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
     * If you try to use this instance afterward, it may give unexpected results.
     */
    public void logout() {
        pingTimer.cancel();
        command("logout", new HashMap<>());
        session = password = username = "";
        if(socket != null) {
            socket.sendClose(0, "Logged out");
            socket.abort();
        }
    }

    /**
     * Close this instance. This should be invoked if the {@link Client} is no more required.
     * If you try to use this instance afterward, it may give unexpected results.
     * <p>Note: This is equivalent to the {@link #logout()} method.</p>
     */
    public void close() {
        logout();
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

    public JSON otp(String email, String mobile) {
        otpEmail = email;
        Map<String, Object> map = new HashMap<>();
        map.put("email", email);
        map.put("mobile", mobile);
        map.put("action", "init");
        JSON r = command("otp", map);
        session = r.getString("session");
        return r;
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

    /**
     * Upload new binary content to the server.
     *
     * @param mimeType Mime-type should be correctly specified because it will not be verified by the server.
     * @param data Data to be uploaded.
     * @return If successfully uploaded (Status == "OK"), the "id" attribute will contain the ID of the new content
     * created.
     */
    public JSON upload(String mimeType, InputStream data) {
        return upload(mimeType, data, null);
    }

    /**
     * Upload some binary content to the server.
     *
     * @param mimeType Mime-type should be correctly specified because it will not be verified by the server.
     * @param data Data to be uploaded.
     * @param streamNameOrID If not blank or null, the corresponding content will be overwritten. It could be
     *                       specified as the content-name or the ID of the content.
     * @return If successfully uploaded (Status == "OK"), the "id" attribute will contain the ID of the content.
     */
    public JSON upload(String mimeType, InputStream data, String streamNameOrID) {
        Map<String, Object> map = new HashMap<>();
        if(streamNameOrID != null && !streamNameOrID.isBlank()) {
            map.put("stream", streamNameOrID);
        }
        map.put("type", mimeType);
        JSON json = command("upload", map);
        if(!"OK".equals(json.getString("status"))) {
            IO.close(data);
            return json;
        }
        data = IO.get(data);
        byte[] bytes;
        try {
            int r;
            while (true) {
                bytes = new byte[65536];
                r = data.read(bytes);
                if(r < 0) {
                    break;
                }
                lastCommandAt = System.currentTimeMillis();
                socket.sendBinary(ByteBuffer.wrap(bytes, 0, r), false);
            }
            lastCommandAt = System.currentTimeMillis();
            socket.sendBinary(ByteBuffer.wrap(new byte[0]), true);
        } catch (IOException e) {
            socket.sendClose(100, "Error");
            socket = null;
            return error(99, e.getMessage());
        } finally {
            IO.close(data);
        }
        return readResponse();
    }

    private JSON command(String command, Map<String, Object> attributes, boolean checkCommand, boolean preserveServerState) {
        boolean sessionRequired = true;
        if (command.equals("register") || command.equals("otp")) {
            Object action = attributes.get("action");
            if (action instanceof String a) {
                sessionRequired = !a.equals("init");
            }
        }
        if (sessionRequired && (username.isEmpty() || session.isEmpty())) {
            return error(0, "Not logged in");
        }
        if(socket == null) {
            return error(1, NOT_CONNECTED);
        }
        if(checkCommand) {
            switch (command) {
                case "file", "stream":
                    return error(2, "Invalid command");
            }
        }
        if(sessionRequired) {
            attributes.put("session", session);
        }
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
                return  error(3,"Can't re-login. Reason: " + status);
            }
            return command(command, attributes, false);
        }
        return r;
    }

    /**
     * Extract fault if any from the given JSON object.
     *
     * @param json JSON object from which fault needs to be extracted.
     * @return Fault instance or null.
     */
    public static Fault getFault(JSON json) {
        Number errorCode = json.getNumber("errorCode");
        if(errorCode != null) {
            String message = json.getString("message");
            if(message != null) {
                return new Fault(errorCode.intValue(), message);
            }
        }
        return null;
    }

    private JSON error(int code, String error) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", "ERROR");
        map.put("message", error);
        map.put("errorCode", 900000 + code);
        return new JSON(map);
    }

    private synchronized JSON post(Map<String, Object> map) {
        if(connectionLatch != null) {
            try {
                connectionLatch.await();
            } catch (InterruptedException ignored) {
            }
        }
        if(socket == null || socket.isOutputClosed()) {
            return error(5,"Connection closed");
        }
        lastCommandAt = System.currentTimeMillis();
        socket.sendText(new JSON(map).toString(), true);
        return readResponse();
    }

    private JSON readResponse() {
        textLatch = new CountDownLatch(1);
        socket.request(1);
        while (true) {
            synchronized (responses) {
                if(!responses.isEmpty()) {
                    try {
                        return new JSON(responses.removeFirst());
                    } catch (Throwable e) {
                        return error(6,"Invalid response");
                    }
                }
            }
            if(socket == null) {
                if(error != null && error instanceof SOException) {
                    return error(99, error.getMessage());
                }
                return error(1, NOT_CONNECTED);
            }
            if(socket.isInputClosed()) {
                return error(5,"Connection closed");
            }
            try {
                textLatch.await();
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Retrieve the stream of data for the [name].
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
     * Retrieve the stream of data for the give file [name].
     * <p>You should have got the [name] from a previous request, or it could be the name of a file in the SO platform.</p>
     *
     * @param name Name of the stream.
     * @return An instance of {@link  Data}. This contains an {@link InputStream} containing binary data if
     * the {@link Data#error()} is <code>null</code>. {@link Data#mimeType()} provides the content-type.
     */
    public Data file(String name) {
        return _stream("file", name);
    }

    /**
     * Run a report and retrieve its output.
     *
     * @param logic Name of the report logic.
     * @param parameters Parameters for the report.
     * @return An instance of {@link  Data}. This contains an {@link InputStream} containing binary data if
     * the {@link Data#error()} is <code>null</code>. {@link Data#mimeType()} provides the content-type.
     */
    public Data report(String logic, Map<String, Object> parameters) {
        Map<String, Object> m;
        if(parameters.get("parameters") != null) {
            m = parameters;
        } else {
            m = new HashMap<>();
            m.put("parameters", parameters);
        }
        return _stream("report", logic, m);
    }

    /**
     * Run a report and retrieve its output.
     *
     * @param logic Name of the report logic.
     * @return An instance of {@link  Data}. This contains an {@link InputStream} containing binary data if
     * the {@link Data#error()} is <code>null</code>. {@link Data#mimeType()} provides the content-type.
     */
    public Data report(String logic) {
        return _stream("report", logic, new HashMap<>());
    }

    private synchronized Data _stream(String command, String name) {
        return _stream(command, name, new HashMap<>());
    }

    private synchronized Data _stream(String command, String name, Map<String, Object> map) {
        if(name != null && !name.isBlank()) {
            map.put(command, name);
        }
        while (currentBinary != null) {
            try {
                if(binaryLatch == null) {
                    Thread.sleep(500);
                } else {
                    binaryLatch.await();
                }
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
        binaryLatch = new CountDownLatch(1);
        binaryFragmentLatch = new CountDownLatch(1);
        socket.request(1);
        return new Data(in, r.getString("type"), null);
    }

    /**
     * Structure to wrap an {@link InputStream} and its content-type. If the stream is absent (i.e., <code>null</code>),
     * the {@link Data#error()} provides the real error message.
     *
     * @param stream An instance of an {@link InputStream}. Please make sure that the stream is closed once data is read
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
            if(webSocket != socket) {
                return null;
            }
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
                if(textLatch != null) {
                    textLatch.countDown();
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
            if(webSocket != socket) {
                return null;
            }
            if(currentBinary != null) {
                synchronized (currentBinary.buffers) {
                    if(!currentBinary.closed) {
                        currentBinary.buffers.add(data);
                    }
                    if(last) {
                        currentBinary.completed = true;
                        currentBinary = null;
                        if(binaryLatch != null) {
                            binaryLatch.countDown();
                        }
                    } else if (socket != null) {
                        socket.request(1);
                    }
                    binaryFragmentLatch.countDown();
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
            if(webSocket != socket) {
                return null;
            }
            error = new SOException("Connection closed, Reason: " + statusCode + " " + reason);
            close();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if(webSocket != socket) {
                return;
            }
            Client.this.error = error;
            close();
        }

        private void close() {
            Client.this.socket = null;
            if(textLatch != null) {
                textLatch.countDown();
            }
            if(binaryLatch != null) {
                binaryLatch.countDown();
            }
        }
    }

    private class BufferedStream extends InputStream {

        final List<ByteBuffer> buffers = new ArrayList<>();
        private ByteBuffer buffer;
        volatile boolean completed = false;
        boolean closed = false;

        private void buf() {
            while (true) {
                synchronized (buffers) {
                    if(!buffers.isEmpty()) {
                        buffer = buffers.removeFirst();
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
                    if(binaryFragmentLatch.await(1000, TimeUnit.MILLISECONDS)) {
                        if(binaryLatch.getCount() != 0) {
                            binaryFragmentLatch = new CountDownLatch(1);
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }

        @Override
        public int read() {
            if(closed) {
                return -1;
            }
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
            if(closed) {
                return -1;
            }
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
            closed = true;
            buffer = null;
            synchronized (buffers) {
                buffers.clear();
            }
        }
    }
}
