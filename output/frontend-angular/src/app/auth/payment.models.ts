// frontend_spec.md

# frontend_spec.md — Historia: Confirmación de pagos de bajo monto (#17, con consumo de #18)

> **Alcance y honestidad.** El contrato de Arquitectura recibido está truncado (sin rutas, códigos ni formato de errores). Para las rutas y códigos uso la tabla de endpoints y los códigos 400/404/409/503/500 del `backend_spec.md`. Los nombres de campos de request y response **no aparecen en ningún documento recibido**. Los deduje del modelo de dominio (`Payment`, `Destination`, `PaymentPreview`) y los marco **SUPUESTO PENDIENTE**. Todos están aislados en `payment.models.ts` y en `PaymentApiService`, para corregirlos en un solo lugar.
> No recibí código del frontend existente. Se asume Angular ≥ 17 (componentes standalone, signals, control flow `@if/@switch/@for`) y que `provideHttpClient()` está configurado en la app.
> **No se compiló ni se ejecutó nada: NO EJECUTADO.** No se recibieron observaciones de revisión previas.

## 1. Pantalla y recorrido

Componente `PaymentFlowComponent` (`app-payment-flow`) con un recorrido de pasos. Cada paso mueve el foco a su encabezado `h2`.

1. **form**: el pagador ingresa destino y monto → botón "Continuar" (llama a `POST /payments/preview`).
2. **confirm**: se muestran identidad del destino y monto devueltos por el servidor → "Confirmar pago" (`POST /payments` con `Idempotency-Key`) o "Editar datos".
3. **processing**: polling a `GET /payments/{id}` hasta estado terminal, sin acciones que dupliquen el cobro.
4. **delayed**: pasaron 60 s (valor del cliente, ver supuestos) o falló la consulta, y el pago puede seguir en curso. Solo ofrece "Consultar de nuevo" y "Ver historial".
5. **result**: un único resultado terminal.
   - `SUCCEEDED`: referencia de transacción y acciones "Ver historial" y "Hacer otro pago".
   - `FAILED` o `EXPIRED`: acciones "Reintentar" y "Volver".

`PaymentHistoryComponent` lista el historial (`GET /payments/history?page&size`) con estados de carga, vacío, error y paginación.

## 2. Campos y validaciones visibles

| Campo | Control | Validaciones del cliente (solo apoyo de UX) | Mensaje visible |
|---|---|---|---|
| Destino (identificador) | `destinationIdentifier` | requerido; no solo espacios | "Ingresa el destino del pago." |
| Monto | `amount` (texto decimal, `inputmode="decimal"`) | requerido; formato numérico con hasta N decimales (N = decimales de la moneda vía `Intl`); mayor que cero | "Ingresa el monto." / "Usa solo números con hasta N decimales. Ejemplo: 12.50" / "El monto debe ser mayor que cero." |
| Servidor (400 con `fieldErrors`) | marca el control | no es regla del cliente | "El monto no está dentro de los límites permitidos para este pago." / "Revisa este dato: no fue aceptado." |

Los datos `providerId`, `channel`, `currency` y `destinationType` **no son campos del formulario**. Llegan por `@Input() context` (SUPUESTO PENDIENTE: origen). **Los límites mínimo y máximo no se validan en el cliente.** No están en el contrato y los decide el servidor (`PaymentLimitsPort`), que responde 400.

## 3. Acciones del usuario

| Acción | Efecto | Protección |
|---|---|---|
| Continuar | Preview | Deshabilitado y con texto "Verificando…" mientras `busy()` |
| Confirmar pago | Crea el intento con `Idempotency-Key` | Deshabilitado mientras `busy()`, más guarda en código. La clave se genera una vez por intento y se **reutiliza** si hubo error de red o 5xx (no duplica el cobro) |
| Editar datos | Vuelve al formulario y descarta la clave | Deshabilitado mientras `busy()` |
| Consultar de nuevo | Reanuda el polling del mismo pago | No crea pagos |
| Reintentar (FAILED/EXPIRED) | Vuelve a "confirm" con **clave nueva** (nuevo intento, D5 de Arquitectura) | Solo visible en estado terminal |
| Volver | Reinicia el recorrido | Solo en estado terminal |
| Ver historial | Emite `viewHistory` | — |

## 4. Estados de pantalla

| Estado | Formulario/Confirmación | Resultado | Historial |
|---|---|---|---|
| Carga | Botón deshabilitado, texto de progreso, `aria-busy`, región `role="status"` | Indicador "Confirmando tu pago…" | "Cargando historial…" |
| Vacío | No aplica (no hay lista). Si el preview no devolviera datos, se trata como error | No aplica | "Aún no tienes pagos en tu historial." |
| Error | Caja `role="alert"` con mensaje traducido, foco en la caja, datos conservados | Paso `delayed` si falla la consulta | Alerta y botón "Reintentar carga" |
| Éxito/terminal | — | Etiqueta provisional + referencia (si `SUCCEEDED`) | Tabla |

Las etiquetas provisionales de estados terminales están en `STATUS_LABELS`. **SUPUESTO PENDIENTE: Producto debe definirlas.**

## 5. Mapeo de errores de API → mensaje (`payment-error.mapper.ts`)

Nunca se muestra `message`, `code` ni detalle técnico del servidor. Se traduce solo por estado HTTP y operación.

| Estado | Operación | Mensaje al usuario |
|---|---|---|
| 0 / red | cualquiera | "No pudimos conectarnos. Revisa tu conexión e inténtalo de nuevo." (en create añade que reintentar no duplica el cobro) |
| 400 | preview/create | "Revisa los datos ingresados. El monto debe estar dentro de los límites permitidos para este canal." + marca de campos si hay `fieldErrors` |
| 401 | cualquiera | "Tu sesión ya no es válida. Inicia sesión nuevamente." + evento `sessionExpired` |
| 403 | cualquiera | "No tienes permiso para realizar esta operación." |
| 404 | preview/create | "No encontramos el destino solicitado." |
| 404 | poll | "No encontramos este pago." |
| 409 | create | "Ya hay un pago en curso o esta operación ya fue registrada. Espera su resultado para no duplicar el cobro." |
| 503 | cualquiera | "El servicio de pagos no está disponible por ahora. Inténtalo en unos minutos." (create: añade que no se duplicará el cobro al reintentar) |
| 500 / otros 5xx | cualquiera | "Ocurrió un problema inesperado. Inténtalo nuevamente." |
| cualquiera | poll | "No pudimos consultar el estado de tu pago. Puede seguir en proceso: no lo repitas, vuelve a consultar." |

El `fieldErrors` del 400 está en el backend como "400 con `fieldErrors`", pero **su forma exacta no se recibió**. El mapper acepta arreglo de `{field}`/strings o un objeto con claves, y solo usa los nombres de campo. **SUPUESTO PENDIENTE (Backend).** El `409` no distingue "pago en curso" de "conflicto de idempotencia" porque el contrato recibido no trae un código; por eso el mensaje es unificado.

## 6. Contrato consumido

| Uso | Ruta (SUPUESTO de ruta, según `backend_spec.md`) | Notas |
|---|---|---|
| Preview | `POST /payments/preview` | Body `PaymentRequest` (SUPUESTO de campos) |
| Crear | `POST /payments` + header `Idempotency-Key` | 201 nuevo / 200 repetición; el cliente trata ambos igual |
| Consultar | `GET /payments/{id}` | Estados `PENDING`, `SUCCEEDED`, `FAILED`, `EXPIRED`. Un estado desconocido se trata como no terminal |
| Historial | `GET /payments/history?page&size` | Forma de `HistoryPageResponse` SUPUESTO |
| Comprobante | `GET /payments/{id}/receipt` | **No se consume en esta entrega** (formato no recibido) |

## 7. Accesibilidad

- Cada campo tiene `<label for>`; las pistas y errores se asocian con `aria-describedby`; `aria-invalid` se activa solo si el campo se tocó y tiene error.
- Los errores de API usan `role="alert"` y se enfocan (`tabindex="-1"`); el progreso usa `role="status"` con `aria-live="polite"`; `aria-busy` marca el contenedor ocupado.
- Gestión del foco: cada cambio de paso enfoca el `h2`; si el formulario es inválido, se enfoca el primer campo inválido.
- Todo es operable por teclado (elementos nativos `button`, `input`, `form`; Enter envía). `:focus-visible` explícito.
- El contraste de los colores del SCSS fue elegido para superar 4.5:1 según cálculo estimado; **no medido con herramienta: NO EJECUTADO**.
- El estado no se comunica solo por color: hay texto y encabezado propios por resultado.
- Los datos del usuario y del servidor se renderizan con interpolación; no hay `innerHTML`.

## 8. Seguridad

- La validación del cliente no sustituye la del servidor. Límites, autorización y duplicados los decide el backend.
- No se guardan tokens ni claves en `localStorage`/`sessionStorage`. La `Idempotency-Key` vive solo en memoria del componente. La autenticación se asume resuelta por la app (interceptor/cookie), fuera de este alcance.
- Sin secretos en el código. `PAYMENTS_API_BASE` es configurable por inyección.

## 9. Trazabilidad con criterios de aceptación y evidencia

| CA | Archivo / elemento | Verificación propuesta (NO EJECUTADO) |
|---|---|---|
| 1. Destino y monto antes de confirmar | `payment-flow.component.html`, bloque `@case ('confirm')`, `dl` con `displayName`, identificador y monto del `PreviewResponse` | Manual: completar el formulario y comprobar que, antes de pulsar "Confirmar pago", se ve el destino y el monto devueltos por el servidor. Backend simulado o real |
| 2. Un único resultado terminal | `payment-api.service.ts` `poll()` (`takeWhile` inclusivo) y `payment-flow.component.ts` `startPolling()`/`result` | `ng test`: `payment-api.service.spec.ts` ("poll se detiene en el primer estado terminal"). Manual: con respuestas PENDING→SUCCEEDED, una sola pantalla de resultado |
| 3. Referencia y historial | Resultado: `html` bloque `SUCCEEDED` (`transactionReference`). Historial: `payment-history.component.*` | Manual: tras un pago exitoso, ver la referencia y luego el pago en el historial. **La persistencia la garantiza el backend** |
| 4. Siguiente acción sin duplicados | `html` botones Reintentar/Volver; `component.ts` `busy()`, guarda en `confirm()`, `idempotencyKey` (misma clave en errores ambiguos, clave nueva en `retry()`) | `ng test`: header `Idempotency-Key` en `create`. Manual: doble clic rápido en "Confirmar" genera una sola petición (pestaña Network); en FAILED/EXPIRED aparecen ambas acciones |
| 5. Caída en checkout 30%→8% | **No verificable en frontend.** Es métrica de negocio/analítica | Requiere medición en producción (Producto/Datos) |
| 6. Evento de activación (MAU) | **Responsabilidad del backend** (`PaymentEventPublisherPort`). El frontend no emite este evento | Verificar en backend. Si Producto quiere eventos de funnel desde el cliente, **SUPUESTO PENDIENTE** (no existe contrato) |

Comandos propuestos: `ng test --watch=false` y `ng build`. **Resultado: NO EJECUTADO.** Hay specs escritas para el mapper de errores y el servicio. **No escribí spec del componente**: su verificación queda como pasos manuales de la tabla.

## 10. Supuestos pendientes

| # | Supuesto | Quién define |
|---|---|---|
| S1 | Rutas exactas y prefijo de API (`PAYMENTS_API_BASE`), según el `backend_spec.md` | Arquitectura/Backend |
| S2 | Nombres y estructura de `PaymentRequest`, `PreviewResponse`, `PaymentResponse`, `HistoryPageResponse` y `ErrorResponse` (incluye forma de `fieldErrors`) | Backend |
| S3 | Origen de `providerId`, `channel`, `currency`, `destinationType` (`context`) y tipos de destino admitidos | Producto/Arquitectura |
| S4 | Etiquetas visibles de estados terminales y textos finales | Producto |
| S5 | Intervalo (2 s) y tope (60 s) de polling del cliente | Producto/Backend |
| S6 | Si la API distingue "pago en curso" de "conflicto de idempotencia" (códigos en 409) | Backend |
| S7 | Enmascarar o no el identificador del destino en pantalla | Seguridad/Producto |
| S8 | Autenticación (interceptor/cookie), `withCredentials`, ruta de login tras 401 (se emite `sessionExpired`) | Arquitectura |
| S9 | Pantalla de comprobante (`/receipt`) y navegación al historial | Producto/Backend |
| S10 | Eventos de funnel desde el cliente | Producto/Datos |
| S11 | Decimales de la moneda: se derivan de `Intl` y asumen que el backend usa la misma unidad menor (`amountMinor`) | Backend/Datos |

## 11. Correcciones

Sin observaciones previas recibidas; no hay correcciones que reportar.