/**
 * Copyright 2012 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.corundumstudio.socketio;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.KEEP_ALIVE;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corundumstudio.socketio.parser.Encoder;
import com.corundumstudio.socketio.parser.Packet;
import com.corundumstudio.socketio.parser.PacketType;
import com.corundumstudio.socketio.transport.XHRPollingClient;

public class AuthorizeHandler extends SimpleChannelUpstreamHandler implements Disconnectable {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // 'UUID' to 'timestamp' mapping
    // this map will be always smaller than 'connectedSessionIds'
    private final Map<UUID, Long> authorizedSessionIds = new ConcurrentHashMap<UUID, Long>();
    private final Set<UUID> connectedSessionIds = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    private final String connectPath;

    private final ObjectMapper objectMapper;
    private final Encoder encoder;

    private final Configuration configuration;
    private final SocketIOListener socketIOListener;

    public AuthorizeHandler(String connectPath, ObjectMapper objectMapper, Encoder encoder,
            SocketIOListener socketIOListener, Configuration configuration) {
        super();
        this.connectPath = connectPath;
        this.objectMapper = objectMapper;
        this.socketIOListener = socketIOListener;
        this.encoder = encoder;
        this.configuration = configuration;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Object msg = e.getMessage();
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;
            Channel channel = ctx.getChannel();
            QueryStringDecoder queryDecoder = new QueryStringDecoder(req.getUri());
            if (HttpMethod.GET.equals(req.getMethod()) && queryDecoder.getPath().equals(connectPath)) {
                authorize(channel, req, queryDecoder.getParameters());
                return;
            }
        }
        ctx.sendUpstream(e);
    }

    private void authorize(Channel channel, HttpRequest req, Map<String, List<String>> params)
            throws IOException {
        removeStaleAuthorizedIds();
        // TODO use common client
        final UUID sessionId = UUID.randomUUID();
        authorizedSessionIds.put(sessionId, System.currentTimeMillis());

        String transports = "xhr-polling,websocket";
        //String transports = "websocket";
        String heartbeatTimeoutVal = String.valueOf(configuration.getHeartbeatTimeout());
        if (configuration.getHeartbeatTimeout() == 0) {
            heartbeatTimeoutVal = "";
        }

        boolean jsonp = false;
        String msg = sessionId + ":" + heartbeatTimeoutVal + ":" + configuration.getCloseTimeout() + ":" + transports;

        List<String> jsonpParam = params.get("jsonp");
        if (jsonpParam != null) {
            jsonp = true;
            msg = "io.j[" + jsonpParam.get(0) + "](" + objectMapper.writeValueAsString(msg) + ");";
        }

        sendResponse(req, channel, sessionId, msg, jsonp);
        log.debug("New sessionId: {} authorized", sessionId);
    }

    private void addHeaders(HttpResponse res, String origin, boolean jsonp) {
        res.addHeader(CONTENT_TYPE, "text/plain; charset=UTF-8");
        res.addHeader(CONNECTION, KEEP_ALIVE);
        if (origin != null) {
            res.addHeader("Access-Control-Allow-Origin", origin);
            res.addHeader("Access-Control-Allow-Credentials", "true");
        }
        if (jsonp) {
            res.addHeader(CONTENT_TYPE, "application/javascript");
        }
    }

    private void sendResponse(HttpRequest req, Channel channel, UUID sessionId, String message, boolean jsonp) {
        HttpResponse res = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        String origin = req.getHeader(HttpHeaders.Names.ORIGIN);
        addHeaders(res, origin, jsonp);

        res.setContent(ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8));
        HttpHeaders.setContentLength(res, res.getContent().readableBytes());

        log.trace("Out message: {} sessionId: {}", new Object[] {message, sessionId});
        channel.write(res);
    }

    public boolean isSessionAuthorized(UUID sessionId) {
        return connectedSessionIds.contains(sessionId)
                    || authorizedSessionIds.containsKey(sessionId);
    }

    /**
     * Remove stale authorized client ids which
     * has not connected during some timeout
     */
    private void removeStaleAuthorizedIds() {
        for (Iterator<Entry<UUID, Long>> iterator = authorizedSessionIds.entrySet().iterator(); iterator.hasNext();) {
            Entry<UUID, Long> entry = iterator.next();
            if (System.currentTimeMillis() - entry.getValue() > 60*1000) {
                iterator.remove();
                log.debug("Authorized sessionId: {} cleared due to connection timeout", entry.getKey());
            }
        }
    }

    public void connect(SocketIOClient client) {
        authorizedSessionIds.remove(client.getSessionId());
        connectedSessionIds.add(client.getSessionId());

        client.send(new Packet(PacketType.CONNECT));
        socketIOListener.onConnect(client);
    }

    @Override
    public void onDisconnect(SocketIOClient client) {
        connectedSessionIds.remove(client.getSessionId());
    }

}