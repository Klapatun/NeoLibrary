# Подводные камни Robolectric 4.12.2 @ sdk 19

Собранные в ISSUES.md (§8.4, §10.3, §11.4, §13.1). Читать при отладке
падающего UI-теста, не при обычном прогоне сьюта.

1. `ShadowActivity.getNextStartedActivity()` **деструктивна** (списывает интент
   из очереди): читать один раз, дальше — сохранённый объект.
2. `ShadowDialog.clickOn(viewId)` принимает **id вью** (`android.R.id.button1`/
   `button2`), а НЕ `AlertDialog.BUTTON_*` (по ним — NPE).
3. В PAUSED-лоопере запуск интента откладывается: после клика по диалогу
   обязательно `shadowOf(Looper.getMainLooper()).idle()`, и только потом
   `getNextStartedActivity()`.
4. API 19: у `Dialog`/`AlertDialog` НЕТ публичных геттеров title/message.
   Title — `shadowOf(dialog).getTitle()`; message —
   `dialog.getWindow().getDecorView().findViewById(android.R.id.message)`.
5. `changeCursor` на НЕ-NULL курсор шлёт `onChanged` (`notifyDataSetChanged`),
   НЕ `onInvalidated` (onInvalidated — только при `changeCursor(null)`).
6. Сравнение `ConstantState` одного PNG, прошедшего через разные инфляционные
   пути (тема), в Robolectric не проходит — для иконок сравнивать доминантный
   непрозрачный цвет картинки (PNG почти монохромные).
7. Жесты: сырые `MotionEvent` DOWN→MOVE→UP через `dispatchTouchEvent`,
   **между событиями — `idle()` main-looper'а** (имитация кадров реального
   инпута), иначе posted-обработчики press-состояния не успеют до UP.
8. `OnTouchListener` на `AbsListView`/`GridView` **никогда** не срабатывает для
   жеста, начатого на строке (view group шлёт событие child'у, кликабельная
   строка поглощает). Если тест жмёт listener'ом по списку — проверять, не
   мёртвый ли это код.
9. Фейк-курсор SAF-импорта: у Robolectric-`BaseCursor` `register*Observer`/
   `getInt`/`getLong` могут бить `UnsupportedOperationException` — обход
   описан в `MainActivityTest` (обслуживать при апгрейде Robolectric).
