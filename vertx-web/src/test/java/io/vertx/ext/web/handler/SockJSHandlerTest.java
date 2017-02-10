/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.handler;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.WebTestBase;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.test.core.TestUtils;
import org.junit.Test;

/**
 * SockJS protocol tests
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class SockJSHandlerTest extends WebTestBase {

  private static final Logger log = LoggerFactory.getLogger(SockJSHandlerTest.class);

  @Override
  public void setUp() throws Exception {
    super.setUp();
    SockJSHandler.installTestApplications(router, vertx);
  }

  @Test
  public void testGreeting() {
    waitFor(2);
    testGreeting("/echo/");
    testGreeting("/echo");
    await();
  }

  private void testGreeting(String uri) {
    client.getNow(uri, resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals("text/plain; charset=UTF-8", resp.getHeader("content-type"));
      resp.bodyHandler(buff -> {
        assertEquals("Welcome to SockJS!\n", buff.toString());
        complete();
      });
    });
  }

  @Test
  public void testNotFound() {
    waitFor(5);

    testNotFound("/echo/a");
    testNotFound("/echo/a.html");
    testNotFound("/echo/a/a");
    testNotFound("/echo/a/a/");
    testNotFound("/echo/a/");
    testNotFound("/echo//");
    testNotFound("/echo///");

    await();
  }

  // https://github.com/vert-x3/vertx-web/issues/77
  @Test
  public void testSendWebsocketContinuationFrames() {
    // Use raw websocket transport
    client.websocket("/echo/websocket", ws -> {

      int size = 65535;

      Buffer buffer1 = TestUtils.randomBuffer(size);
      Buffer buffer2 = TestUtils.randomBuffer(size);

      ws.writeFrame(io.vertx.core.http.WebSocketFrame.binaryFrame(buffer1, false));
      ws.writeFrame(io.vertx.core.http.WebSocketFrame.continuationFrame(buffer2, true));

      Buffer received = Buffer.buffer();

      ws.handler(buff -> {
        received.appendBuffer(buff);
        if (received.length() == size * 2) {
          testComplete();
        }
      });

    });

    await();
  }

  /**
   * {@link io.vertx.ext.web.handler.sockjs.SockJSSocket#write(Buffer)} should be able to handle large buffers by
   * splitting them into multiple frames
   */
  @Test
  public void testWriteContinuationFrames() {
    final Buffer fixedReplyBuffer = TestUtils.randomBuffer(100_000);

    router.route("/echo2/*").handler(SockJSHandler.create(vertx,
            new SockJSHandlerOptions().setMaxBytesStreaming(4096)).socketHandler(sock -> sock.handler(buffer -> sock.write(fixedReplyBuffer))));
    // Use raw websocket transport
    client.websocket("/echo2/websocket", ws -> {
      Buffer request = Buffer.buffer("test");
      ws.writeBinaryMessage(request);

      Buffer clientBuffer = Buffer.buffer(fixedReplyBuffer.length());
      ws.handler(buff -> {
        log.info(String.format("Received buffer of size %s", buff.length()));
        clientBuffer.appendBuffer(buff);
        if (clientBuffer.equals(fixedReplyBuffer)) {
          testComplete();
        }
      });

    });

    await();
  }

  /**
   * {@link io.vertx.ext.web.handler.sockjs.SockJSSocket#handler(Handler)} should combine websocket frames before
   * delivering it to user code
   */
  @Test
  public void testReadContinuationFrames() {
    final Buffer fixedReplyBuffer = Buffer.buffer("reply");

    int size = 65535;
    Buffer requestBuffer1 = TestUtils.randomBuffer(size);
    Buffer requestBuffer2 = TestUtils.randomBuffer(size);
    Buffer requestBuffer3 = TestUtils.randomBuffer(size);
    Buffer mergedRequestBuffer = Buffer.buffer()
      .appendBuffer(requestBuffer1)
      .appendBuffer(requestBuffer2)
      .appendBuffer(requestBuffer3);

    router.route("/echo2/*").handler(SockJSHandler.create(vertx,
      new SockJSHandlerOptions().setMaxBytesStreaming(4096)).socketHandler(sock -> sock.handler(buffer -> {
        assertEquals("Multiple client frames should have been combined into a single server message",
          mergedRequestBuffer, buffer);
        sock.write(fixedReplyBuffer);
    })));

    // Use raw websocket transport
    client.websocket("/echo2/websocket", ws -> {

      ws.writeFrame(io.vertx.core.http.WebSocketFrame.binaryFrame(requestBuffer1, false));
      ws.writeFrame(io.vertx.core.http.WebSocketFrame.continuationFrame(requestBuffer2, false));
      ws.writeFrame(io.vertx.core.http.WebSocketFrame.continuationFrame(requestBuffer3, true));

      ws.handler(buff -> {
        assertEquals("Incorrect reply received", fixedReplyBuffer, buff);
        testComplete();
      });

    });

    await();
  }

  private void testNotFound(String uri) {
    client.getNow(uri, resp -> {
      assertEquals(404, resp.statusCode());
      complete();
    });
  }

  @Test
  public void testCookiesRemoved() throws Exception {
    router.route("/cookiesremoved/*").handler(SockJSHandler.create(vertx)
          .socketHandler(sock -> {
            MultiMap headers = sock.headers();
            String cookieHeader = headers.get("cookie");
            assertNotNull(cookieHeader);
            assertEquals("JSESSIONID=wibble", cookieHeader);
            testComplete();
          }));
    MultiMap headers = new CaseInsensitiveHeaders();
    headers.add("cookie", "JSESSIONID=wibble");
    headers.add("cookie", "flibble=floob");

    client.websocket("/cookiesremoved/websocket", headers, ws -> {
      String frame = "foo";
      ws.writeFrame(io.vertx.core.http.WebSocketFrame.textFrame(frame, true));
    });

    await();
  }

}
