# Routing Provider Decision

**Status:** Valhalla public demo selected for the personal V1 prototype after the owner chose motorcycle routing and asked for a free option. Keep the endpoint behind `RouteProvider`; use low request volume and preserve an option to change the base URL.

## Requirements

- Search destinations and return road geometry plus turn instructions.
- Recalculate online after an off-route event, with a cooldown and only while connected.
- Save route geometry and maneuvers on-device for offline following.
- Prefer motorcycle-appropriate roads for India.
- Keep map rendering and routing providers replaceable.

## V1 decision: Valhalla motorcycle routing

Valhalla is MIT-licensed and supports a `motorcycle` costing model. FOSSGIS hosts a public, full-planet Valhalla demo at `https://valhalla1.openstreetmap.de`. The Valhalla project describes that service as public and fair-use, with rate limits comparable to OSRM/Nominatim demos. For apps published to end users, the project asks developers to identify the client with an `X-Client-Id` header and contact them through GitHub Discussions. Self-hosting has no Valhalla software license fee, but still needs paid or personally operated hosting and map-data maintenance.

The public demo is open to the public under fair-use limits; its project documentation does not publish a per-call price or uptime guarantee. The personal V1 can use it without a paid API contract, but this is not a permanent free-service guarantee. Use it at low volume; do not treat it as a production dependency for wider distribution. Route results and maneuvers will be saved only on this owner's device. Use the required OpenStreetMap attribution. Keep route search and reroute requests separate from Tripper Wi-Fi so the phone uses its normal internet network.

## Future providers

| Provider | Fit | Material limits |
| --- | --- | --- |
| Kurviger through GraphHopper Directions API | Motorcycle-specific routing, considers motorcycle access, returns detailed turns and supports navigation apps. | Custom package required; no public API package price. Terms allow temporary client-side route-result caching but do not publish a retention duration. Confirm offline saved-route retention in the package before production. Must show `Powered by Kurviger` and OSM attribution with the route. |
| HERE Routing API v8 | `scooter` mode uses roads open to motorcycles and supports India coverage. | HERE Base/Limited plans exclude Asset Management, including locating/displaying assets and route calculations. HERE platform terms also restrict vehicle-screen integration except for projection of what is shown on the phone screen. An eligible written plan would be required for this app's screen-off TFT output. |
| Google Routes | Motorcycle mode is available in India. | The planned MapLibre basemap and indefinite offline route storage do not fit the reviewed Maps Platform display and caching rules. |
| Self-hosted Valhalla | Same open-source motorcycle engine; no routing API license fee. | Requires a maintained server and map-data updates, which add hosting and setup costs. |

## Destination search

Geocoding is a separate interface. For the personal V1, use submitted searches only through a compatible OSM geocoder; do not implement autocomplete against public Nominatim. Identify the app, attribute OpenStreetMap, rate-limit requests and cache responses according to its current usage policy. Search is best-effort, not a service guarantee.

## Evidence

- Valhalla motorcycle costing and route API: <https://valhalla.github.io/valhalla/api/route/api-reference/> and <https://github.com/valhalla/valhalla/blob/master/docs/docs/api/openapi.yaml>
- Valhalla software license: <https://github.com/valhalla/valhalla/blob/master/COPYING>
- Valhalla public demo and fair-use/client-identification guidance: <https://github.com/valhalla/valhalla/blob/master/docs/docs/start/introduction.md>
- Kurviger motorcycle API capabilities, intended navigation-app use and attribution: <https://kurviger.com/en/api_use>
- GraphHopper API caching and navigation-app terms: <https://www.graphhopper.com/terms/>
- HERE scooter routing and plan restrictions: <https://docs.here.com/routing/docs/routing-v8-scooter-routing> and <https://www.here.com/get-started/pricing/base-plan-restrictions>
- HERE vehicle-system integration and result-storage terms: <https://legal.here.com/us-en/terms/here-platform-terms>
- Google two-wheeler routing and supported-region notes: <https://developers.google.com/maps/documentation/routes/route_two_wheel>
- Nominatim usage policy: <https://operations.osmfoundation.org/policies/nominatim/>

## Traceability

- `DEC-ROUTE-01` (`dec:route-provider`): use the public Valhalla motorcycle demo for low-volume personal V1 routing, with a replaceable endpoint and no uptime assumption.
- `IMP-ROUTE-01` (`imp:valhalla-motorcycle-provider`): `app/src/main/java/com/dharmil/reconnector/navigation/provider/ValhallaMotorcycleRouteProvider.kt` and `NavigationEndpointSettings.kt`.
