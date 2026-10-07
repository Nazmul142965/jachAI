package com.jachai.shared.config;

import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Stable, explicit page JSON that retains the existing v1 frontend's flat pagination fields. */
@RestControllerAdvice
public class StablePageResponseAdvice implements ResponseBodyAdvice<Object> {
    public record PagePayload<T>(List<T> content, int number, int size, int numberOfElements,
                                 long totalElements, int totalPages, boolean first,
                                 boolean last, boolean empty) {
        static <T> PagePayload<T> from(Page<T> page) {
            return new PagePayload<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getNumberOfElements(), page.getTotalElements(), page.getTotalPages(),
                    page.isFirst(), page.isLast(), page.isEmpty());
        }
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        return body instanceof Page<?> page ? PagePayload.from(page) : body;
    }
}
