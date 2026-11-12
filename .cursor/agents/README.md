# Субагенты Neo Library (Cursor)

Роли конвейера `.agents/PIPELINE.md`. Все модели — из **недельного пула Premium** (Composer / Grok), без Claude, Gemini и GPT.

| Субагент | Роль | Модель | Вызов |
|----------|------|--------|--------|
| `neo-explore` | Поиск по коду | `composer-2.5-fast` | `/neo-explore …` |
| `neo-analytics` | План / [REFAC] | `grok-4.7-high` | `/neo-analytics …` |
| `neo-tests` | Тесты и проверка сьюта | `composer-2.5-fast` | `/neo-tests …` |
| `neo-developer-low` | Продакшн (простые задачи) | `composer-2.5-fast` | `/neo-developer-low …` |
| `neo-developer` | Продакшн (сложные задачи) | `grok-4.7-high` | `/neo-developer …` |
| `neo-refactor` | [REFAC] | `grok-4.7-high` | `/neo-refactor …` |

Уровень и субагент D задаёт аналитик в плане (§2, скил `analytics` §2.1).

Рекомендация для **встроенного Explore** (Settings → Agents → Subagents): **Composer 2.5 (Fast)** или **Inherit**, если родитель уже на Composer/Grok.

Аналог OpenCode: `.opencode/agents/*.md` (там permission sandbox). Здесь — промпты + `model:` для Cursor.
