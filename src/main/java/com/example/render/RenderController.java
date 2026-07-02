package com.example.render;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * HTTP surface: {@code POST /render} with a JSON payload (e.g. a receipt), returns {@code image/png}.
 *
 * <p>Pipeline: JSON body → Thymeleaf template → HTML → {@link RenderDispatcher} (Blitz/Rust fast path,
 * WebView fallback). The request carries only data; template/geometry/backend come from config.
 *
 * <p>Runs on the blocking executor because a render may briefly park (WebView cell or native call) —
 * deliberate backpressure; overload becomes a fast 503, not an unbounded queue.
 */
@Controller("/render")
public class RenderController {

    private static final Logger LOG = LoggerFactory.getLogger(RenderController.class);

    private final RenderDispatcher dispatcher;

    public RenderController(RenderDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Post
    @Produces(MediaType.IMAGE_PNG)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<byte[]> render(@Body Map<String, Object> payload,
                                       @QueryValue @Nullable String size) {
        if (payload == null || payload.isEmpty()) {
            return HttpResponse.badRequest();
        }
        try {
            byte[] png = dispatcher.render(payload, size);
            return HttpResponse.ok(png).contentType(MediaType.IMAGE_PNG);
        } catch (CompletionException ce) {
            return mapError(ce.getCause() == null ? ce : ce.getCause());
        } catch (Exception e) {
            return mapError(e);
        }
    }

    private HttpResponse<byte[]> mapError(Throwable t) {
        if (t instanceof InvalidRequestException) {
            // Client error (payload too large/complex). Message is safe to surface; no payload echoed.
            LOG.warn("rejecting invalid request: {}", t.getMessage());
            return HttpResponse.<byte[]>status(io.micronaut.http.HttpStatus.BAD_REQUEST);
        }
        if (t instanceof RejectedRenderException) {
            LOG.warn("shedding request: {}", t.getMessage());
            return HttpResponse.<byte[]>status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "1");
        }
        LOG.error("render failed", t);
        return HttpResponse.serverError();
    }
}