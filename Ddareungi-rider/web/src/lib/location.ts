export const MAX_LOCATION_ACCURACY = 500;

/** Retry a coarse fix once, retaining the better response if the retry fails. */
export function requestLocation(
  geolocation: Pick<Geolocation, "getCurrentPosition">,
  success: PositionCallback,
  failure: PositionErrorCallback,
) {
  const options: PositionOptions = {enableHighAccuracy: true, timeout: 15000, maximumAge: 0};
  geolocation.getCurrentPosition(first => {
    if (Number.isFinite(first.coords.accuracy) && first.coords.accuracy <= MAX_LOCATION_ACCURACY) {
      success(first);
      return;
    }
    geolocation.getCurrentPosition(second => {
      success(Number.isFinite(second.coords.accuracy) &&
        (!Number.isFinite(first.coords.accuracy) || second.coords.accuracy < first.coords.accuracy) ? second : first);
    }, () => success(first), options);
  }, failure, options);
}
