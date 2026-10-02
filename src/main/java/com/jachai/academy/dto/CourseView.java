package com.jachai.academy.dto;

import com.jachai.academy.model.Course;
import java.time.Instant;

public record CourseView(String id, String name, String description, Instant createdAt) {
    public static CourseView from(Course course) {
        return new CourseView(course.id(), course.name(), course.description(), course.createdAt());
    }
}
