package com.jachai.platform.dto;

import java.time.Instant;

public record PlatformStorageView(Instant generatedAt, long dataBytes, long indexBytes,
                                  long estimatedLogicalBytes, long warningThresholdBytes,
                                  boolean warning) { }
