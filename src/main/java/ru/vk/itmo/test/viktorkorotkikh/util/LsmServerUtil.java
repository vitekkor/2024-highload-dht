package ru.vk.itmo.test.viktorkorotkikh.util;

import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.util.ByteArrayBuilder;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.NoSuchElementException;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;

public class LsmServerUtil {
    public static final String TIMESTAMP_HEADER = "X-Entity-Timestamp";
    public static final String TIMESTAMP_HEADER_WITH_COLON = TIMESTAMP_HEADER + ':';

    private LsmServerUtil() {
    }

    public static String timestampToHeader(long timestamp) {
        return TIMESTAMP_HEADER + ": " + timestamp;
    }

    public static Response mergeReplicasResponses(
            final Request originalRequest,
            final NodeResponse[] responses,
            final int ack
    ) {
        switch (originalRequest.getMethod()) {
            case Request.METHOD_GET -> {
                return mergeGetResponses(originalRequest, responses, ack);
            }
            case Request.METHOD_PUT -> {
                return mergePutResponses(originalRequest, responses, ack);
            }
            case Request.METHOD_DELETE -> {
                return mergeDeleteResponses(originalRequest, responses, ack);
            }
            default -> throw new IllegalStateException("Unsupported method " + originalRequest.getMethod());
        }
    }

    private static Response mergeGetResponses(Request originalRequest, NodeResponse[] responses, int ack) {
        long maxTimestamp = -1;
        NodeResponse lastValue = null;
        int successfulResponses = 0;
        for (NodeResponse response : responses) {
            if (response == null) continue;
            final long valueTimestamp = getTimestamp(response);
            if (valueTimestamp > maxTimestamp) {
                maxTimestamp = valueTimestamp;
                lastValue = response;
            }
            if (response.statusCode() == HTTP_OK || response.statusCode() == HTTP_NOT_FOUND) {
                successfulResponses++;
            }
        }
        if (successfulResponses < ack) {
            return LSMConstantResponse.notEnoughReplicas(originalRequest);
        }
        if (lastValue == null) {
            lastValue = firstNotNull(responses);
        }
        return switch (lastValue.statusCode()) {
            case HTTP_OK -> Response.ok(lastValue.body());
            case HTTP_BAD_REQUEST -> LSMConstantResponse.badRequest(originalRequest);
            case HTTP_NOT_FOUND -> LSMConstantResponse.notFound(originalRequest);
            case HTTP_ENTITY_TOO_LARGE -> LSMConstantResponse.entityTooLarge(originalRequest);
            case 429 -> LSMConstantResponse.tooManyRequests(originalRequest);
            case HTTP_GATEWAY_TIMEOUT -> LSMConstantResponse.gatewayTimeout(originalRequest);
            default -> LSMConstantResponse.serviceUnavailable(originalRequest);
        };
    }

    private static Response mergePutResponses(
            Request originalRequest,
            NodeResponse[] responses,
            int ack
    ) {
        if (hasNotEnoughReplicas(responses, ack)) {
            return LSMConstantResponse.notEnoughReplicas(originalRequest);
        }
        return LSMConstantResponse.created(originalRequest);
    }

    private static Response mergeDeleteResponses(
            Request originalRequest,
            NodeResponse[] responses,
            int ack
    ) {
        if (hasNotEnoughReplicas(responses, ack)) {
            return LSMConstantResponse.notEnoughReplicas(originalRequest);
        }
        return LSMConstantResponse.accepted(originalRequest);
    }

    private static boolean hasNotEnoughReplicas(NodeResponse[] responses, int ack) {
        int successfulResponses = 0;
        for (NodeResponse response : responses) {
            if (response == null) continue;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                successfulResponses++;
            }
        }
        return successfulResponses < ack;
    }

    private static long getTimestamp(final NodeResponse response) {
        String timestamp = response.getHeader(TIMESTAMP_HEADER);
        if (timestamp == null) {
            return -1;
        }
        return Long.parseLong(timestamp);
    }

    private static NodeResponse firstNotNull(NodeResponse[] responses) {
        for (NodeResponse response : responses) {
            if (response != null) return response;
        }
        throw new NoSuchElementException();
    }

    public static int copyMemorySegmentToByteArrayBuilder(MemorySegment memorySegmentBody, ByteArrayBuilder builder) {
        return copyMemorySegmentToByteArrayBuilder(memorySegmentBody, 0, builder);
    }

    public static int copyMemorySegmentToByteArrayBuilder(
            MemorySegment memorySegment,
            int memorySegmentOffset,
            ByteArrayBuilder builder
    ) {
        int estimatedCapacityInBuffer = builder.capacity() - builder.length();
        int toWrite = memorySegment.byteSize() > estimatedCapacityInBuffer
                ? estimatedCapacityInBuffer
                : (int) memorySegment.byteSize() - memorySegmentOffset;
        MemorySegment.copy(
                memorySegment,
                ValueLayout.JAVA_BYTE,
                memorySegmentOffset,
                builder.buffer(),
                builder.length(),
                toWrite
        );
        builder.setLength(builder.length() + toWrite);
        return toWrite;
    }
}
