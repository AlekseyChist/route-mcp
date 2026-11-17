# route-mcp

MCP-сервер на Kotlin: строит велосипедные маршруты по дорогам через BRouter (OpenStreetMap) и отдаёт GPX, который Claude может сразу загрузить в COROS.

## Инструменты

| Tool | Что делает |
|---|---|
| `geocode` | Название места → координаты (Nominatim/OSM) |
| `plan_route` | Точки → маршрут по дорогам: дистанция, набор, время + упрощённый GPX. Полный GPX сохраняется в `~/Routes`. `avoid` — круглые зоны-исключения, чтобы объехать конкретную улицу |
| `find_popular_segments` | Точка → популярные сегменты Strava рядом (Segments Explore), с координатами старта/финиша для `plan_route` |

## Как это устроено

```
Claude ──JSON-RPC по stdin/stdout──► route-mcp.jar ──HTTP──► brouter.de / nominatim
   │                                      │
   └──── uploadRouteFile (COROS MCP) ◄────┘ упрощённый GPX
```

- `Main.kt`: создаём `Server`, регистрируем tools, подключаем `StdioServerTransport`.
- `BRouterApi.kt`: запрос `GET /brouter?lonlats=lon,lat|lon,lat&profile=...&format=geojson`, парсинг через kotlinx.serialization.
- `Geocoder.kt`: Nominatim, обязательный `User-Agent`.
- `Gpx.kt`: алгоритм Дугласа–Пекера (упрощение трека до N точек) и сборка GPX.
- `StravaApi.kt`: Segments Explore + OAuth refresh_token flow (см. раздел «Strava» ниже).

Важно для stdio-серверов: **в stdout нельзя писать ничего, кроме протокола**. Одна лишняя строка — и клиент
не может разобрать ответ. На договорённость «не писать `println`» полагаться нельзя: библиотеки о ней не знают.
Например `kotlin-logging` при инициализации печатает в stdout строку `kotlin-logging: initializing...`.

Поэтому `main()` первым делом забирает настоящий stdout себе под транспорт, а `System.out` уводит в stderr:
после этого любой `println` из любой зависимости уходит в лог и протокол не ломает.

## Сборка

```bash
./gradlew shadowJar
# → build/libs/route-mcp.jar
```

Нужен JDK 17+. Подойдёт JBR из Android Studio:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

## Проверка руками

```bash
npx @modelcontextprotocol/inspector java -jar build/libs/route-mcp.jar
```

## Подключение к Claude Desktop

`~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "routes": {
      "command": "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java",
      "args": ["-jar", "/Users/<you>/Projects/route-mcp/build/libs/route-mcp.jar"]
    }
  }
}
```

После перезапуска Claude Desktop инструменты будут доступны в чатах, связанных с этим маком.

## Переменные окружения

- `BROUTER_URL`: свой сервер BRouter (по умолчанию `https://brouter.de`)
- `ROUTES_DIR`: куда сохранять полные GPX (по умолчанию `~/Routes`)
- `STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET`, `STRAVA_REFRESH_TOKEN`: см. ниже
- `STRAVA_CACHE_FILE`: куда сохранять обновлённый access/refresh token между перезапусками
  (по умолчанию `~/.secrets/route-mcp-strava-cache.json`)

## Strava: настройка `find_popular_segments`

⚠️ **Сейчас не работает.** Хитмэп Strava закрыт для сторонних приложений — это не воспроизвести.
Был доступен официальный **Segments Explore** (собственный рейтинг популярности Strava по сегментам
в прямоугольнике вокруг точки), но Strava задеприцировала `GET /segments/explore` с 1 сентября 2026
для всех, кроме приложений с одобренным Extended Access (возможно, платным — см. их объявления об
изменениях API 2026 года). Проверено на реальном токене с подтверждённым scope `read`: `/athlete`
отвечает, `/segments/explore` и `/athlete/activities` — `401`, а прямой `GET /segments/{id}`
по известному id продолжает работать. Инструмент оставлен в коде на случай, если Extended Access
будет одобрен или Strava передумает; до этого он возвращает понятную ошибку, а не падает молча.

Если решишь добиваться Extended Access или просто настраиваешь токены — шаги те же:

1. **Приложение**: [strava.com/settings/api](https://www.strava.com/settings/api) → создать (бесплатно).
   Даёт `Client ID` (число, не секрет) и `Client Secret`.
2. **Авторизация со scope `read`** — без него и `segments/explore`, и `athlete/activities` отвечают
   `401 Authorization Error` (видно только профиль). Открыть в браузере:

   ```
   https://www.strava.com/oauth/authorize?client_id=ТВОЙ_ID&response_type=code&redirect_uri=http://localhost/exchange_token&approval_prompt=force&scope=read
   ```

   Авторизовать, скопировать `code=...` из адресной строки после редиректа (localhost не обязан отвечать —
   код виден в URL), обменять на токены:

   ```bash
   curl -X POST https://www.strava.com/oauth/token \
     -d client_id=ТВОЙ_ID -d client_secret=ТВОЙ_SECRET \
     -d code=КОД_ИЗ_URL -d grant_type=authorization_code
   ```

   Ответ содержит `refresh_token` — его и нужно сохранить.
3. **Конфиг**: добавить `env` в запись `routes` в `claude_desktop_config.json`:

   ```json
   "routes": {
     "command": "...",
     "args": ["-jar", "..."],
     "env": {
       "STRAVA_CLIENT_ID": "...",
       "STRAVA_CLIENT_SECRET": "...",
       "STRAVA_REFRESH_TOKEN": "..."
     }
   }
   ```

`access_token` живёт 6 часов и обновляется сервером автоматически по `refresh_token`; новый
`refresh_token` (Strava иногда присылает другой) сохраняется в `STRAVA_CACHE_FILE` и переживает
перезапуск. `client_id`/`client_secret` в кэш не попадают — это постоянные значения приложения.

## Идеи для развития

- Свой BRouter в Docker на маке + сегменты для Сербии (`E20_N40.rd5`): без лимитов публичного сервера
- Tool `roundtrip`: кольцо заданной длины от точки
- Свой профиль `.brf` под шоссе с объездом грунта
- HTTP-транспорт + ngrok → удалённый коннектор для claude.ai (работает с телефона без привязки к маку)
