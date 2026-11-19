package com.ddareungi.global.response;

/** {@code GET /api/v1/status}: which data mode the server runs in ({@code infra} or {@code sample}). */
public record ServiceStatusResponse(String mode) {}
