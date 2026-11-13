package com.ddareungi.routing.presentation;

/** {@code GET /api/v1/routes/status}: whether route planning is enabled and its providers are configured. */
public record RouteStatusResponse(boolean configured) {}
