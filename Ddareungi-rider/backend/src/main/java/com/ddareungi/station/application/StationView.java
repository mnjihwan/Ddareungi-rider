package com.ddareungi.station.application;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.domain.Station;
import org.jspecify.annotations.Nullable;

/** A station with its realtime status and age at the request's evaluation instant (see {@link StationViewMapper}). */
public record StationView(Station station, RealtimeFreshness realtimeStatus, @Nullable Long realtimeAgeSeconds) {}
