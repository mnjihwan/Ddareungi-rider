export interface Station {
  stationId: string;
  stationName: string;
  address: string;
  location: { latitude: number; longitude: number };
  rackTotalCount: number;
  availableBikeCount: number;
  distanceMeters: number;
  updatedAt: string;
}
