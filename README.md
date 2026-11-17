# route-mcp

MCP-сервер на Kotlin: строит велосипедные маршруты по дорогам через BRouter (OpenStreetMap) и отдаёт GPX, который Claude может сразу загрузить в COROS.

## Инструменты

| Tool | Что делает |
|---|---|
| `geocode` | Название места → координаты (Nominatim/OSM) |
| `plan_route` | Точки → маршрут по дорогам: дистанция, набор, время + упрощённый GPX. Полный GPX сохраняется в `~/Routes` |

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

## Идеи для развития

- Свой BRouter в Docker на маке + сегменты для Сербии (`E20_N40.rd5`): без лимитов публичного сервера
- Tool `roundtrip`: кольцо заданной длины от точки
- Свой профиль `.brf` под шоссе с объездом грунта
- HTTP-транспорт + ngrok → удалённый коннектор для claude.ai (работает с телефона без привязки к маку)
