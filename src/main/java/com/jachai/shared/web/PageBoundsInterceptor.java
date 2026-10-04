package com.jachai.shared.web;

import com.jachai.shared.error.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Arrays;

@Component
public class PageBoundsInterceptor implements HandlerInterceptor {
    private static final int MAX_PAGE = 500;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"GET".equals(request.getMethod()) || !(handler instanceof HandlerMethod method)) return true;
        boolean pagedRoute = Arrays.stream(method.getMethodParameters()).anyMatch(parameter ->
                parameter.hasParameterAnnotation(RequestParam.class)
                        && parameter.hasParameterAnnotation(Max.class)
                        && parameter.getParameterAnnotation(Max.class).value() == MAX_PAGE);
        if (!pagedRoute) return true;
        String[] values = request.getParameterValues("page");
        if (values == null) return true;
        if (values.length != 1) throw invalidPage();
        try {
            int page = Integer.parseInt(values[0]);
            if (page < 0 || page > MAX_PAGE) throw invalidPage();
        } catch (NumberFormatException exception) {
            throw invalidPage();
        }
        return true;
    }

    private ApiException invalidPage() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
    }
}
