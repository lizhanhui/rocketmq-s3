/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.rocketmq.proxy.grpc.v2.consumer;

import apache.rocketmq.v2.Code;
import apache.rocketmq.v2.ReceiveMessageRequest;
import apache.rocketmq.v2.ReceiveMessageResponse;
import apache.rocketmq.v2.Status;
import com.automq.rocketmq.common.trace.TraceHelper;
import com.automq.rocketmq.proxy.metrics.ProxyMetricsManager;
import com.automq.rocketmq.proxy.model.ProxyContextExt;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.apache.rocketmq.client.consumer.PopResult;
import org.apache.rocketmq.proxy.common.ContextVariable;
import org.apache.rocketmq.proxy.common.ProxyContext;
import org.apache.rocketmq.proxy.grpc.v2.common.ResponseBuilder;
import org.apache.rocketmq.proxy.grpc.v2.consumer.ReceiveMessageResponseStreamWriter;
import org.apache.rocketmq.proxy.processor.MessagingProcessor;

public class ExtendReceiveMessageResponseStreamWriter extends ReceiveMessageResponseStreamWriter {
    private final Span rootSpan;

    public ExtendReceiveMessageResponseStreamWriter(ProxyContextExt ctx, MessagingProcessor messagingProcessor,
        StreamObserver<ReceiveMessageResponse> observer) {
        super(messagingProcessor, observer);
        Tracer tracer = ctx.tracer().get();
        rootSpan = tracer.spanBuilder("ReceiveMessage")
            .setNoParent()
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(ContextVariable.PROTOCOL_TYPE, ctx.getProtocolType())
            .setAttribute(ContextVariable.ACTION, ctx.getAction())
            .setAttribute(ContextVariable.CLIENT_ID, ctx.getClientID())
            .startSpan();
        ctx.attachSpan(rootSpan);
    }

    private void recordRpcLatency(ProxyContext ctx, Code code) {
        ProxyContextExt context = (ProxyContextExt) ctx;
        ProxyMetricsManager.recordRpcLatency(ctx.getProtocolType(), ctx.getAction(), code.name().toLowerCase(), context.getElapsedTimeNanos(), context.suspended());
    }

    @Override
    public void writeAndComplete(ProxyContext ctx, ReceiveMessageRequest request, PopResult popResult) {
        super.writeAndComplete(ctx, request, popResult);
        switch (popResult.getPopStatus()) {
            case FOUND:
                if (popResult.getMsgFoundList().isEmpty()) {
                    recordRpcLatency(ctx, Code.MESSAGE_NOT_FOUND);
                    rootSpan.setAttribute("code", Code.MESSAGE_NOT_FOUND.name().toLowerCase());
                } else {
                    recordRpcLatency(ctx, Code.OK);
                    rootSpan.setAttribute("code", Code.OK.name().toLowerCase());
                }
                break;
            case POLLING_FULL:
                recordRpcLatency(ctx, Code.TOO_MANY_REQUESTS);
                rootSpan.setAttribute("code", Code.TOO_MANY_REQUESTS.name().toLowerCase());
                break;
            case NO_NEW_MSG:
            case POLLING_NOT_FOUND:
            default:
                recordRpcLatency(ctx, Code.MESSAGE_NOT_FOUND);
                rootSpan.setAttribute("code", Code.MESSAGE_NOT_FOUND.name().toLowerCase());
                break;
        }
        rootSpan.setStatus(StatusCode.OK);
        rootSpan.end();
    }

    @Override
    public void writeAndComplete(ProxyContext ctx, Code code, String message) {
        super.writeAndComplete(ctx, code, message);
        recordRpcLatency(ctx, code);
        rootSpan.setStatus(StatusCode.OK);
        rootSpan.setAttribute("code", code.name().toLowerCase());
        rootSpan.end();
    }

    @Override
    public void writeAndComplete(ProxyContext ctx, ReceiveMessageRequest request, Throwable throwable) {
        super.writeAndComplete(ctx, request, throwable);
        Status status = ResponseBuilder.getInstance().buildStatus(throwable);
        recordRpcLatency(ctx, status.getCode());
        rootSpan.setAttribute("code", status.getCode().name().toLowerCase());
        TraceHelper.endSpan((ProxyContextExt) ctx, rootSpan, throwable);
    }
}
