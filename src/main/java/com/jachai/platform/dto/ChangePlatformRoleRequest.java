package com.jachai.platform.dto;

import jakarta.validation.constraints.NotNull;

public record ChangePlatformRoleRequest(@NotNull Boolean platformAdmin) { }
