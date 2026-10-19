# 현재 로컬 아키텍처

```text
PC / 모바일 브라우저
  -> React + Vite (5173)
  -> /api 프록시
  -> Spring Boot (8080)
  -> StationSearchPort
       local: 메모리 샘플
       infra: MySQL 대여소 후보 + Redis 최신 수량 + 최종 거리 필터

bikeseoul_data (8081)
  -> Flyway 스키마 초기화
  -> 서울시 마스터/실시간 API
  -> MySQL station / station_history

백엔드 실시간 수집기
  -> 서울시 bikeList
  -> Redis (5분 TTL, 매분 갱신)
```

앱 배포와 네이티브 SDK는 현재 범위에서 제외합니다. Elasticsearch는 현재 실행 경로에 포함하지 않습니다.
MySQL의 좌표 범위로 후보를 조회한 뒤 실제 구면 거리로 반경을 재검증합니다.
Redis 수량은 pipeline으로 조회하고, 해시 저장과 TTL 설정은 Lua로 원자적으로 수행합니다.
수량 미수집은 null이며 0대와 구별합니다. 과거 시간별 재고를 현재 수량으로 대체하지 않습니다.

지도·장소 검색·도보/자전거/도보 길찾기와 검색 입력 보정은 구현되어 있습니다. 프로덕션 v5 명세는 더 넓은 설계 범위이며, Elasticsearch와 미래 잔여 수량 예측 API는 아직 연결하지 않았습니다. Python AI는 별도 오프라인 수요 실험 단계입니다.
