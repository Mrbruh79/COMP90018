# Stage 4: location, venue and map refactor

Pranjal owns this stage. It preserves the existing location-facing behavior while separating Android, Firebase, OpenStreetMap and event-policy responsibilities.

## Boundaries

- `LocationProvider` exposes a platform-neutral `LocationFix`.
- `FusedLocationProvider` is the only class that calls Google Play services for a current GPS fix.
- `LocationDistanceCalculator` owns Haversine distance calculation.
- `LocationQuality` validates reported horizontal accuracy; the event policy retains its 50-metre threshold.
- `FirebaseVenueRepository` combines an injected location provider with `FirestoreVenueRemoteDataSource` and the pure `VenueMatcher`.
- `PlaceSearchRepository` isolates place search from Compose; `NominatimPlaceSearchRepository` implements the existing rate-limited OpenStreetMap search.
- `EventLocationPicker` owns location search, current-position selection, map pin selection and related UI errors. `EventFormScreen` retains only the selected venue value needed to submit the form.

## Behavior intentionally preserved

- The Activity or Compose permission launcher requests permission before invoking `LocationProvider`.
- A missing fix returns the same user-facing fallback guidance.
- Event GPS admission requires accuracy of 50 metres or better.
- Admission includes reported uncertainty by comparing distance with `event radius + accuracy`.
- Generic venue lookup returns the first Firestore venue whose configured radius contains the current coordinates.
- Place search remains limited to five Australian Nominatim results and one request per second.
- No Firebase schema, Firestore rules or event document fields changed.

## Verification

Automated tests cover coordinate validation, Haversine distance, accuracy boundaries, venue matching, signed-out and missing-fix short-circuits, and event boundary uncertainty. Physical-device verification should still cover permission denial, disabled location services, current-location selection, OSM search/map selection and GPS event entry.
