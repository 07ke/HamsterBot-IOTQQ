package cn.hamster3.bot.core;

import cn.hamster3.bot.event.*;
import cn.hamster3.bot.listener.EventHandler;
import cn.hamster3.bot.listener.Listener;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.logging.Logger;

public class BotCore {
    private static Logger logger = Logger.getLogger("BOT");

    private String host;
    private int port;

    private long qq;
    private Socket socket;

    private ArrayList<Listener> listeners;

    public BotCore(String host, int port, long qq) throws URISyntaxException {
        listeners = new ArrayList<>();
        this.host = host;
        this.port = port;
        this.qq = qq;

        IO.Options options = new IO.Options();
        options.transports = new String[]{"websocket"};
        socket = IO.socket("http://" + host + ":" + port, options);

        socket.on("connect", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                JsonObject object = new JsonObject();
                logger.info("链接成功: " + object);
                callEvent(new SocketConnectedEvent(BotCore.this, object));

                socket.emit("GetWebConn", String.valueOf(qq), (Ack) args1 -> {
                    logger.info("注册完成, 服务器返回: " + args1[0]);
                    callEvent(new SocketRegisteredEvent(BotCore.this, null));
                });
                socket.off("connect", this);
            }
        });
        socket.on("OnFriendMsgs", args -> callEvent(new FriendMessageEvent(this, JsonParser.parseString(args[0].toString()).getAsJsonObject())));
        socket.on("OnGroupMsgs", args -> callEvent(new GroupMessageEvent(this, JsonParser.parseString(args[0].toString()).getAsJsonObject())));
        socket.on("OnEvents", args -> callEvent(new OtherEvent(this, JsonParser.parseString(args[0].toString()).getAsJsonObject())));
    }

    public void start() {
        socket.connect();
        logger.info("正在连接至IOTQQ...");

    }

    public JsonObject sendMessage(JsonObject data) throws IOException {
        URL url = new URL("http://" + host + ":" + port + "/v1/LuaApiCaller?qq=" + qq + "&funcname=SendMsg&timeout=10");

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Charset", "UTF-8");
        connection.setRequestProperty("Connection", "Keep-Alive");
        connection.setUseCaches(false);

        connection.setDoOutput(true);
        connection.getOutputStream().write(data.toString().getBytes(StandardCharsets.UTF_8));
        return JsonParser.parseReader(new InputStreamReader(connection.getInputStream())).getAsJsonObject();
    }

    public boolean addListener(Listener listener) {
        return listeners.add(listener);
    }

    public boolean removeListener(Listener listener) {
        return listeners.remove(listener);
    }

    public void callEvent(Event event) {
        ArrayList<InvokeObject> invokeObjects = new ArrayList<>();
        for (Listener listener : listeners) {
            for (Method method : listener.getClass().getMethods()) {
                if (method.getParameterCount() != 1) {
                    continue;
                }
                EventHandler eventHandler = method.getAnnotation(EventHandler.class);
                if (eventHandler == null) {
                    continue;
                }
                if (!method.getParameterTypes()[0].isInstance(event)) {
                    continue;
                }
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                invokeObjects.add(new InvokeObject(listener, method));
            }
        }
        invokeObjects.sort(Comparator.comparingInt(InvokeObject::getPriority));

        for (InvokeObject object : invokeObjects) {
            if (event.isCancelled() && !object.isIgnoreCancelled()) {
                continue;
            }
            object.invoke(event);
        }
    }
}

