package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.test.viktorkorotkikh.util.LSMConstantResponse;

import java.io.IOException;

public class LSMCustomSession extends HttpSession {
    private LSMRangeWriter lsmRangeWriter;

    public LSMCustomSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    @Override
    public synchronized void sendResponse(Response response) throws IOException {
        Request handling = this.handling;
        if (handling == null) {
            throw new IOException("Out of order response");
        }

        server.incRequestsProcessed();

        boolean keepAlive = LSMConstantResponse.keepAlive(handling);

        writeResponse(response, handling.getMethod() != Request.METHOD_HEAD);
        if (!keepAlive) scheduleClose();

        this.handling = pipeline.pollFirst();
        handling = this.handling;

        if (handling != null) {
            if (handling == FIN) {
                scheduleClose();
            } else {
                server.handleRequest(handling, this);
            }
        }
    }

    @Override
    protected void processWrite() throws Exception {
        super.processWrite();
        sendNextRangeChunks();
    }

    public void sendRangeResponse(LSMRangeWriter lsmRangeWriter) throws IOException {
        Request handling = this.handling;
        if (handling == null) {
            throw new IOException("Out of order response");
        }
        server.incRequestsProcessed();

        this.lsmRangeWriter = lsmRangeWriter;
        sendNextRangeChunks();

        if (this.lsmRangeWriter.remaining() > 0) {
            this.handling = pipeline.pollFirst();
            if (handling == FIN) {
                scheduleClose();
            } else {
                server.handleRequest(handling, this);
            }
        }
    }

    private void sendNextRangeChunks() throws IOException {
        if (lsmRangeWriter == null) return;
        while (queueHead == null && lsmRangeWriter.remaining() > 0) {
            ByteArrayBuilder chunk = lsmRangeWriter.nextChunk();
            write(chunk.buffer(), 0, chunk.length());
        }

        if (lsmRangeWriter.remaining() <= 0) {
            this.handling = pipeline.pollFirst();
            if (handling != null) {
                if (handling == FIN) {
                    scheduleClose();
                } else {
                    server.handleRequest(handling, this);
                }
            }
        }
    }
}
