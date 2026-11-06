# user_story.md

# Historia de usuario: BFF de la wallet Perú MVP sobre BCP Wallet Mock

> **Estado del documento:** propuesta de arquitectura, no implementación.
> **Origen:** Épica `packages/epic/peru-wallet-mvp.sp.md` y sus Issues #2 a #11.
> **Convención:** `[HECHO]` es lo que consta en el Issue. `[PROPUESTA]` es una decisión de diseño que se somete a revisión. `[PENDIENTE]` es un supuesto que ningún rol ha confirmado y que no debe tratarse como definido.

---

## 0. Verificación previa: qué existe y qué falta

**Limitación explícita.** Para redactar este documento solo tuve el texto del Issue y de la épica. **No tuve acceso al código del repositorio ni al contenido de `packages/bcp-wallet-mock-openapi.yaml`.** Por eso:

- Los únicos paths del mock que puedo citar son los nombrados en el Issue: `POST /kyc/verifications`, `GET /payments`, `/control/events`, `/callbacks/payments` y `POST /control/reset`.
- El Issue solo menciona por nombre de área KYC, wallets, pagos, QR, callbacks, ledger, soporte y conciliación. **No conozco** los paths, esquemas, códigos de error ni estados exactos del resto. Cada vez que el diseño depende de ellos queda marcado `[PENDIENTE: verificar en OpenAPI v0.3.0]`.

**Existe `[HECHO]`:**
- Mock BCP Wallet v0.3.0 desplegado en `https://bcp-wallet-mock.yefri.workers.dev`. No tiene autenticación y los importes van en céntimos de PEN.
- Grupo asignado al equipo: `https://bcp-wallet-mock.yefri.workers.dev/groups/1`.
- Brechas conocidas del contrato, listadas en el Issue.

**Falta `[HECHO según Issue: todas las casillas sin marcar]`:**
- Todas las historias #2 a #7 y la fundación técnica #8 a #11.
- Asumo, sin poder verificarlo, que el repositorio de producto está sin construir (greenfield). **Si ya existe código, este diseño debe contrastarse con él antes de implementar.**

---

## 1. Solución propuesta

Se construye un **BFF (Backend For Frontend) propio**, en arquitectura hexagonal, entre la app de la wallet y el BCP Wallet Mock. El BFF no duplica lo que el mock ya hace. Aporta lo que el mock no tiene:

| Brecha del mock (Issue) | Responsabilidad del BFF |
|---|---|
| No hay pasos de onboarding, solo `POST /kyc/verifications` | El BFF modela y persiste el progreso del onboarding. Los pasos son suyos. |
| No existe el estado "expirado" | El BFF lo **deriva** (timeout en `PENDING` o `QR_EXPIRED`). |
| `Payment` sin fechas, `GET /payments` sin filtros ni paginación | El BFF guarda `createdAt` y expone listados filtrados y paginados desde su propia persistencia. |
| No hay listado por comercio | El BFF lista por comercio desde sus registros. Solo `merchant-demo` y `contact-demo` existen en el mock. |
| No hay webhooks push ni firmas | Un **poller** interno consume `/control/events` y `/callbacks/payments`. |
| Conciliación sin corte diario ni canal | El BFF guarda **instantáneas** de conciliación. |
| No hay disputas ni escritura de soporte | El BFF registra solicitudes de reverso internamente. **No resuelve disputas** (ver §5 y §6). |
| Sin idempotencia garantizada `[PENDIENTE: verificar si el mock acepta clave de idempotencia]` | El BFF implementa idempotencia propia y no depende del mock. |

**Principios de diseño**
1. **Idempotencia primero.** Todo `POST` que mueve dinero exige `Idempotency-Key`. El riesgo "reintentos sin idempotencia" es el primero de la épica.
2. **Estados asíncronos explícitos.** Un pago nunca queda "ambiguo". Siempre expone `status`, `statusReason` y `updatedAt`.
3. **Multirriel.** El dominio no asume un riel único. El riel es un dato (`rail`) y los límites se resuelven por proveedor/riel desde configuración.
4. **Importes** siempre como entero en céntimos de PEN (`amountCents`), igual que el mock.
5. **El mock es un adaptador de salida intercambiable.** El dominio no conoce sus paths ni su JSON.

---

## 2. Componentes y límites

### 2.1 Estructura hexagonal `[PROPUESTA]`

La regla de dependencia es `infrastructure → application → domain`, nunca al revés. El lenguaje y el framework dependen del stack de #8 `[PENDIENTE]`. Los nombres de capa siguen el mandato del equipo.

```
domain/
  model/
    Onboarding, OnboardingStep, Wallet, Payment, PaymentStatus,
    Money (amountCents + currency), Destination, QrCharge,
    IdempotencyRecord, ReconciliationSnapshot, ReversalRequest,
    AnalyticsEvent, ProviderLimit
  port/
    input/
      StartOnboardingUseCase, VerifyKycUseCase, ActivateWalletUseCase,
      GetOnboardingUseCase,
      CreatePaymentUseCase, GetPaymentUseCase, ListPaymentsUseCase,
      CreateQrChargeUseCase, ListMerchantPaymentsUseCase,
      SyncPaymentEventsUseCase,
      CreateReconciliationSnapshotUseCase, GetReconciliationSnapshotUseCase,
      RequestReversalUseCase,
      TrackAnalyticsEventUseCase, GetAppConfigUseCase,
      CheckUpstreamHealthUseCase
    output/
      WalletBackendPort      (KYC, wallets, pagos, QR, eventos, conciliación del backend)
      OnboardingRepositoryPort
      PaymentRepositoryPort
      IdempotencyRepositoryPort
      ReconciliationSnapshotRepositoryPort
      ReversalRequestRepositoryPort
      AnalyticsEventRepositoryPort
      AppConfigPort
      ClockPort              (para derivar "expirado" y testear tiempos)
application/
  service/
    OnboardingService, PaymentService, MerchantQrService,
    PaymentEventSyncService, ReconciliationService,
    ReversalService, AnalyticsService, ConfigService, HealthService
infrastructure/
  adapter/
    input/
      rest/        (controllers + DTOs + mapeo de errores)
      scheduler/   (poller de eventos: invoca SyncPaymentEventsUseCase)
    output/
      bcpmock/     (cliente HTTP del mock, implementa WalletBackendPort)
      persistence/ (repositorios, implementan los puertos de repositorio)
  config/          (URL base del grupo, timeouts, límites, intervalos)
```

### 2.2 Límites de responsabilidad

| Componente | Responsable de | NO responsable de |
|---|---|---|
| **Controllers REST** | Validar forma del request (tipos, obligatorios), traducir a comandos de caso de uso, mapear excepciones de dominio a `{code, message}`. | Reglas de negocio, llamadas al mock. |
| **Servicios de aplicación** | Orquestar: idempotencia, límites, persistencia, llamada al puerto del backend, emisión de eventos analíticos. Dependen **solo** de `domain/port/`. | HTTP, SQL, formatos del mock. |
| **Dominio** | Estados, transiciones válidas, derivación de `EXPIRED`, validación de `Money`, resolución de límites. | Cualquier I/O. |
| **Adaptador `bcpmock`** | Traducir puerto ↔ HTTP del mock, timeouts, mapeo de errores del mock a errores de dominio, base URL = `…/groups/1` por configuración. | Lógica de negocio, reintentos de pagos (la política de reintento vive en el servicio). |
| **Adaptador de persistencia** | Guardar progreso de onboarding, pagos (con `createdAt`), claves de idempotencia, instantáneas, solicitudes de reverso, eventos analíticos y configuración. | Reglas de negocio. |
| **Poller** | Disparar periódicamente la sincronización de eventos. | Interpretar eventos (lo hace el servicio). |

### 2.3 Fronteras entre roles
- **Frontend** consume solo el contrato del §3. No habla con el mock.
- **Backend** implementa dominio, aplicación y adaptadores.
- **Datos** define el esquema físico, las restricciones de unicidad y los índices del §6.

---

## 3. Contrato de API (BFF)

Base: `/api/v1`. Formato JSON UTF-8. Todas las fechas en ISO-8601 UTC (`2026-11-06T17:16:38Z`). Todos los importes en `amountCents` (entero ≥ 0) con `currency: "PEN"`.

**Autenticación de clientes del BFF: `[PENDIENTE]`.** El mock no tiene autenticación y el Issue no define cómo se autentica el usuario ante el BFF. Este contrato identifica a la persona por `onboardingId` y `walletId`. **Mientras no se defina, cualquier cliente podría consultar cualquier id.** Es una decisión de seguridad que debe cerrarse antes de producción.

### 3.1 Convenciones transversales

**Formato de error (todos los endpoints):**
```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Texto en español apto para mostrar o registrar",
    "traceId": "b1f0c2...",
    "details": [ { "field": "amountCents", "issue": "debe ser mayor o igual a 1" } ]
  }
}
```
`details` es opcional. `traceId` también viaja en el header `X-Request-Id`.

**Errores comunes:**

| HTTP | `code` | Mensaje | Cuándo |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | "La solicitud contiene datos inválidos." | Campo faltante, tipo o formato incorrecto. |
| 404 | `RESOURCE_NOT_FOUND` | "El recurso solicitado no existe." | Id desconocido. |
| 502 | `UPSTREAM_ERROR` | "El proveedor devolvió un error inesperado." | Respuesta inválida o 5xx del mock. |
| 503 | `UPSTREAM_UNAVAILABLE` | "El proveedor no está disponible. Intenta nuevamente." | Mock inaccesible. |
| 504 | `UPSTREAM_TIMEOUT` | "El proveedor no respondió a tiempo." | Timeout. **En pagos el resultado es incierto** (ver `PaymentService`). |
| 500 | `INTERNAL_ERROR` | "Error interno." | No controlado. |

**Mapeo de errores del mock a códigos del BFF: `[PENDIENTE: verificar los códigos de error de OpenAPI v0.3.0]`.** Solo conozco por el Issue que existe el código `QR_EXPIRED`.

**Estados de pago del BFF `[PROPUESTA]`:**
`PENDING`, `CONFIRMED`, `FAILED`, `EXPIRED` (derivado), `REVERSED`.
**El mapeo desde los estados reales del mock está `[PENDIENTE]`.** Del Issue solo consta que existe `PENDING`.

`EXPIRED` se deriva por cualquiera de estas dos condiciones:
- El pago está `PENDING` y `now − createdAt > pendingTimeoutSeconds` (valor `[PENDIENTE]`, configurable).
- El mock devuelve `QR_EXPIRED`.

---

### 3.2 Onboarding (#2)

**Pasos `[PROPUESTA]`, definidos por el BFF:** `KYC_VERIFICATION` → `WALLET_ACTIVATION` → `COMPLETED`. Estado global: `IN_PROGRESS | COMPLETED | REJECTED`. La lista exacta de pasos debe confirmarla Frontend.

#### `POST /api/v1/onboarding`: inicia onboarding y guarda datos de identidad

| Campo (body) | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `documentNumber` | string | Sí | 1–20 caracteres. En entorno mock debe cumplir `^TEST-.+$` (documentos ficticios del Issue). El formato real (DNI, CE) es `[PENDIENTE]`. |
| `fullName` | string | Sí | 2–120 caracteres, sin solo espacios. |

Los campos que exige el mock en KYC `[PENDIENTE: verificar esquema]` pueden ampliar este body.

- **201** `{ "onboardingId": "string", "status": "IN_PROGRESS", "currentStep": "KYC_VERIFICATION", "createdAt": "…", "updatedAt": "…" }`
- **400** `VALIDATION_ERROR`
- **409** `ONBOARDING_ALREADY_EXISTS`: "Ya existe un onboarding para este documento." Incluye `details: [{ "onboardingId": "…" }]`. La regla de unicidad por documento es `[PENDIENTE: Datos/Producto]`.

#### `POST /api/v1/onboarding/{onboardingId}/kyc-verification`: ejecuta KYC contra el mock (sin body)
Internamente llama a `POST /kyc/verifications` del mock.

- **200** `{ "onboardingId", "status", "currentStep": "WALLET_ACTIVATION", "kyc": { "result": "APPROVED" }, "updatedAt" }`
- **200** si el KYC ya estaba aprobado: devuelve el estado actual sin reejecutar (operación idempotente por estado).
- **422** `KYC_REJECTED`: "No pudimos verificar tu identidad." El onboarding pasa a `status: REJECTED`.
- **409** `ONBOARDING_STEP_INVALID`: "Este paso no corresponde al progreso actual." (por ejemplo, onboarding ya `REJECTED`).
- **404**, **502/503/504** (ver §3.1).

La forma de la respuesta KYC del mock (`APPROVED`/`REJECTED` u otra) es `[PENDIENTE]`.

#### `POST /api/v1/onboarding/{onboardingId}/activation`: activa la wallet (sin body)
Requiere KYC aprobado. Crea la wallet en el mock. **El endpoint de creación de wallet del mock es `[PENDIENTE: verificar en OpenAPI]`.**

- **200** `{ "onboardingId", "status": "COMPLETED", "currentStep": "COMPLETED", "wallet": { "walletId": "string", "currency": "PEN", "status": "ACTIVE" }, "updatedAt" }`
- **409** `ONBOARDING_STEP_INVALID` si el KYC no está aprobado.
- **502/503/504**.

#### `GET /api/v1/onboarding/{onboardingId}`: consulta de progreso (permite retomar el flujo)
- **200** mismo payload que los anteriores, más `completedSteps: ["KYC_VERIFICATION", …]`.
- **404** `RESOURCE_NOT_FOUND`.

---

### 3.3 Pagos de bajo monto (#3, #6, #7)

#### `POST /api/v1/payments`: crear y confirmar un pago

**Headers**

| Header | Obligatorio | Validación |
|---|---|---|
| `Idempotency-Key` | **Sí** | string de 8–64 caracteres, `[A-Za-z0-9_-]`. Recomendado: UUID generado por el cliente **por intención de pago** (no por reintento). |

**Body**

| Campo | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `walletId` | string | Sí | Existente y con wallet `ACTIVE`. |
| `amountCents` | integer | Sí | ≥ 1 y ≤ límite aplicable (ver abajo). |
| `currency` | string | Sí | Solo `"PEN"`. |
| `destination.type` | enum | Sí | `QR` \| `CONTACT`. |
| `destination.qrId` | string | Si `type=QR` | No vacío. |
| `destination.contactId` | string | Si `type=CONTACT` | No vacío. El mock solo conoce `contact-demo`. |
| `description` | string | No | ≤ 140 caracteres. |

Los nombres de campo equivalentes del mock y la separación en "crear" y "confirmar" son `[PENDIENTE]`. Si el mock exige dos llamadas, el BFF las orquesta dentro de este mismo endpoint.

**Límites `[PROPUESTA]`:** `límite aplicable = min(límite regulatorio, límite del proveedor/riel)`, resuelto desde configuración (#11). **Los valores numéricos son `[PENDIENTE]`; no los invento.** El Issue indica que el límite del proveedor puede ser menor que el regulatorio.

**Respuestas**
- **201** pago creado (la primera vez que se procesa la clave):
```json
{
  "paymentId": "string",
  "walletId": "string",
  "amountCents": 1500,
  "currency": "PEN",
  "status": "PENDING | CONFIRMED | FAILED",
  "statusReason": null,
  "rail": null,
  "destination": { "type": "QR", "qrId": "string" },
  "createdAt": "…",
  "updatedAt": "…",
  "expiresAt": null
}
```
- **Replay** con la misma clave y el mismo payload: devuelve **el mismo código de estado y cuerpo que la primera respuesta**, con el header `Idempotent-Replayed: true`. **No** se crea un segundo pago.

`rail` es `null` hasta que se confirme que el mock expone el riel `[PENDIENTE]`. `expiresAt` se calcula solo si hay timeout configurado.

**Errores**

| HTTP | `code` | Mensaje | Condición |
|---|---|---|---|
| 400 | `IDEMPOTENCY_KEY_MISSING` | "Falta el header Idempotency-Key." | Header ausente. |
| 400 | `VALIDATION_ERROR` | (ver §3.1) | Body inválido o `Idempotency-Key` con formato inválido. |
| 404 | `RESOURCE_NOT_FOUND` | | `walletId` desconocido. |
| 409 | `WALLET_NOT_ACTIVE` | "La wallet no está activa." | Onboarding incompleto. |
| 409 | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | "Ya hay una solicitud en proceso con esta clave." | Misma clave aún procesándose. Incluye `Retry-After`. |
| 422 | `IDEMPOTENCY_KEY_REUSED` | "La clave de idempotencia ya se usó con datos distintos." | Misma clave, distinto hash del payload. |
| 422 | `AMOUNT_EXCEEDS_LIMIT` | "El monto supera el límite permitido." | `amountCents` > límite aplicable. `details` incluye el límite. |
| 422 | `INSUFFICIENT_FUNDS` | "Saldo insuficiente." | `[PENDIENTE: confirmar que el mock lo señala así]`. |
| 422 | `QR_EXPIRED` | "El código QR expiró." | El mock devuelve `QR_EXPIRED`. |
| 422 | `PAYMENT_REJECTED` | "El pago fue rechazado." | Rechazo del proveedor por causas no clasificadas. `statusReason` lleva el motivo. |
| 502/503/504 | ver §3.1 | | Ver tratamiento de timeout abajo. |

**Regla de idempotencia `[PROPUESTA]`:**
- Alcance de unicidad: `(walletId, Idempotency-Key)`.
- Se persiste el registro **antes** de llamar al mock, con estado `IN_PROGRESS` y el hash del payload (campos canónicos ordenados). La unicidad la garantiza la restricción en base de datos, no una lectura previa. Así se cubre la concurrencia.
- Tras la respuesta del mock se actualiza el registro a `COMPLETED` y se guarda la respuesta.
- **Timeout o caída entre "llamar al mock" y "persistir resultado":** el pago puede haber ocurrido sin que el BFF lo sepa. El BFF **no devuelve error definitivo**. Devuelve el pago con `status: PENDING` y `statusReason: "UNCONFIRMED_WITH_PROVIDER"` y lo resuelve después con el poller. Un reintento del cliente con la misma clave **no vuelve a llamar al mock a ciegas**. Primero consulta el estado del pago.
- Cómo correlacionar un pago incierto con el del mock (referencia propia, o escanear `GET /payments` sin filtros) es `[PENDIENTE: Backend]`.
- Retención de claves: `[PENDIENTE]`. Se propone un mínimo de 24 h.

#### `GET /api/v1/payments/{paymentId}`
Devuelve el pago desde el BFF. Si está `PENDING`, intenta refrescar el estado con el mock (path `[PENDIENTE]`) y aplica la derivación de `EXPIRED`.

- **200** mismo payload que arriba, más `failure` cuando `status=FAILED`:
```json
"failure": { "code": "QR_EXPIRED", "userMessage": "El código QR expiró. Pide uno nuevo.", "retryable": false }
```
- **404** `RESOURCE_NOT_FOUND`.

#### `GET /api/v1/payments`: listado desde persistencia del BFF

| Query | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `walletId` | string | Sí | |
| `status` | enum | No | Uno de los estados del BFF. |
| `from`, `to` | datetime | No | `from ≤ to`. |
| `limit` | integer | No | 1–100, por defecto 20. |
| `cursor` | string | No | Opaco, entregado por la respuesta anterior. |

- **200** `{ "items": [Payment…], "nextCursor": "string|null" }`, orden `createdAt` descendente.
- **400** `VALIDATION_ERROR`.

Solo se listan pagos creados a través del BFF, ya que el mock no tiene fechas ni filtros.

#### `POST /api/v1/payments/{paymentId}/reversal-requests`: solicitar reverso (#7)

| Campo | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `reason` | enum | Sí | `CUSTOMER_REQUEST` \| `DUPLICATE` \| `MERCHANT_ERROR` \| `OTHER` |
| `comment` | string | No | ≤ 280 caracteres. |

- **202** `{ "reversalRequestId": "string", "paymentId": "string", "status": "REQUESTED", "createdAt": "…" }`
- **409** `PAYMENT_NOT_REVERSIBLE`: "Este pago no puede reversarse en su estado actual." (por ejemplo `FAILED`, `EXPIRED`, `REVERSED`).
- **409** `REVERSAL_ALREADY_REQUESTED`.
- **404**.

**Límite de esta capacidad:** si el mock expone una operación de reverso `[PENDIENTE: verificar]`, el BFF la ejecuta y pasa el pago a `REVERSED`. Si no, la solicitud **solo queda registrada** para tratamiento operativo. El Issue dice que **no hay flujo de disputas ni escritura de acciones de soporte**, así que el BFF no promete resolución automática.

---

### 3.4 Cobro a comercios con QR (#4)

El mock solo conoce `merchant-demo`.

#### `POST /api/v1/merchants/{merchantId}/qr-charges`

| Campo | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `amountCents` | integer | Sí | ≥ 1 y ≤ límite aplicable. |
| `description` | string | No | ≤ 140 caracteres. |
| `ttlSeconds` | integer | No | 30–3600. Los rangos válidos son `[PENDIENTE]`. |

- **201** `{ "qrChargeId": "string", "merchantId": "merchant-demo", "qrId": "string", "amountCents": 1500, "currency": "PEN", "status": "ACTIVE", "createdAt": "…", "expiresAt": "…" }`
- **404** `MERCHANT_NOT_FOUND`: "Comercio no encontrado." Cualquier id distinto de `merchant-demo` dará este error mientras use el mock.
- **422** `AMOUNT_EXCEEDS_LIMIT`.

**Que el mock soporte QR con importe y expiración fijados por el comercio es `[PENDIENTE: verificar OpenAPI]`.**

#### `GET /api/v1/merchants/{merchantId}/payments`: visibilidad de estados para el comercio

Query: `status`, `from`, `to`, `limit` (1–100, por defecto 20), `cursor`.

- **200** `{ "items": [ { "paymentId", "qrChargeId", "amountCents", "currency", "status", "statusReason", "createdAt", "updatedAt" } ], "nextCursor": "…" }`
- **404** `MERCHANT_NOT_FOUND`.

No se expone información del pagador más allá de lo mínimo. Qué datos ve el comercio es `[PENDIENTE: Producto/Legal]`.

---

### 3.5 Sincronización de eventos (interno, #4/#6)

No hay endpoint HTTP público. Un `scheduler` (adaptador de entrada) invoca `SyncPaymentEventsUseCase` cada `eventPollIntervalSeconds` (`[PENDIENTE]`). El caso de uso consume `/control/events` y `/callbacks/payments` por el puerto `WalletBackendPort`.

- **Idempotente:** reprocesar un evento ya aplicado no cambia el estado.
- Una transición no válida (por ejemplo, de `CONFIRMED` a `PENDING`) se **ignora y se registra**, no se aplica.
- **Riesgo:** el Issue no indica si `/control/events` es apto para uso continuo o solo para control/pruebas. Debe confirmarlo Backend `[PENDIENTE]`. Tampoco hay firmas, así que los eventos **no son verificables** en origen.

---

### 3.6 Conciliación operativa (#5)

#### `POST /api/v1/reconciliation/snapshots`: toma una instantánea

| Campo | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `businessDate` | string `YYYY-MM-DD` | No | No futura. Por defecto, la fecha actual en `America/Lima` `[PENDIENTE: confirmar zona horaria]`. |

- **201**
```json
{
  "snapshotId": "string",
  "businessDate": "2026-11-06",
  "takenAt": "…",
  "totals": { "count": 0, "amountCents": 0, "byStatus": { "CONFIRMED": { "count": 0, "amountCents": 0 } } },
  "discrepancies": [ { "paymentId": "string", "type": "STATUS_MISMATCH | MISSING_IN_PROVIDER | MISSING_IN_BFF", "bffStatus": "…", "providerStatus": "…" } ]
}
```
- **400** `VALIDATION_ERROR`.
- **502/503/504**.

#### `GET /api/v1/reconciliation/snapshots`
Query: `from`, `to` (fechas), `limit`, `cursor`. **200** `{ "items": [...], "nextCursor": "…" }`.

#### `GET /api/v1/reconciliation/snapshots/{snapshotId}`
**200** (payload de arriba) · **404** `RESOURCE_NOT_FOUND`.

**Límite:** el mock **no tiene corte diario ni canal**. La instantánea es una foto del BFF comparada con los datos de conciliación y ledger del mock en ese momento. **No es un cierre contable oficial ni auditable.** La comparación con los datos de conciliación del mock depende de endpoints `[PENDIENTE: verificar OpenAPI]`.

---

### 3.7 Instrumentación del funnel y configuración (#11)

#### `POST /api/v1/analytics/events`

| Campo | Tipo | Obligatorio | Validación |
|---|---|---|---|
| `eventName` | enum | Sí | Ver lista `[PROPUESTA]` abajo. |
| `occurredAt` | datetime | Sí | No más de 24 h en el futuro respecto al reloj del servidor. |
| `onboardingId` | string | No | |
| `walletId` | string | No | |
| `properties` | object | No | Máx. 2 KB serializado. **No incluir datos personales** (`documentNumber`, `fullName`). |

Eventos `[PROPUESTA]`: `onboarding_started`, `kyc_submitted`, `kyc_approved`, `kyc_rejected`, `wallet_activated`, `checkout_started`, `payment_submitted`, `payment_confirmed`, `payment_failed`, `qr_charge_created`.

El BFF **también emite del lado servidor** los eventos de onboarding y pago, para que el funnel no dependa solo del cliente.

- **202** `{ "eventId": "string" }`
- **400** `VALIDATION_ERROR` (por ejemplo `eventName` desconocido).

#### `GET /api/v1/config`
**200**
```json
{
  "currency": "PEN",
  "onboardingSteps": ["KYC_VERIFICATION", "WALLET_ACTIVATION"],
  "payment": { "pendingTimeoutSeconds": null, "providerLimits": [ { "rail": null, "maxAmountCents": null } ] },
  "qr": { "defaultTtlSeconds": null }
}
```
Los valores `null` indican **valores aún no definidos `[PENDIENTE]`**. No se inventan cifras. En el MVP la configuración es de solo lectura vía API. La edición se hace por seed o migración `[PENDIENTE: confirmar]`.

---

### 3.8 Verificación del grupo en la nube (#9)

#### `GET /api/v1/health/upstream`
**200** `{ "group": 1, "baseUrl": "https://bcp-wallet-mock.yefri.workers.dev/groups/1", "reachable": true, "latencyMs": 120, "checkedAt": "…" }`
**503** `UPSTREAM_UNAVAILABLE` con el mismo cuerpo de error estándar.

**Qué operación del mock se usa como sonda es `[PENDIENTE]`.** Debe ser de **solo lectura** y **nunca `POST /control/reset`**, que borra todos los datos del grupo 1, compartido entre quienes lo usan. El adaptador no debe exponer ni invocar `reset` fuera de un perfil de pruebas coordinado.

---

## 4. Ejemplos de petición y respuesta

### 4.1 Caso válido: pago por QR

**Petición**
```http
POST /api/v1/payments HTTP/1.1
Content-Type: application/json
Idempotency-Key: 7c1d2e4a-91f0-4c33-8a55-0b6d1e9f2a10

{
  "walletId": "wlt_001",
  "amountCents": 1500,
  "currency": "PEN",
  "destination": { "type": "QR", "qrId": "qr_123" },
  "description": "Almuerzo"
}
```
Los ids son ilustrativos. Su formato real es `[PENDIENTE]`.

**Recorrido**
1. **Controller REST** (`infrastructure/adapter/input`): valida header y body (formato de clave, `amountCents` entero ≥ 1, `currency=PEN`, `destination` coherente). Construye el comando y llama a `CreatePaymentUseCase` (puerto de entrada).
2. **`PaymentService`** (`application/service`):
   1. Calcula el hash canónico del payload.
   2. `IdempotencyRepositoryPort` inserta `(walletId, key, hash, IN_PROGRESS)`. La restricción de unicidad impide duplicados concurrentes. La inserción tiene éxito, así que es una solicitud nueva.
   3. Obtiene la wallet y verifica que esté `ACTIVE`.
   4. `AppConfigPort` entrega los límites. El dominio verifica `amountCents ≤ límite aplicable`.
   5. `PaymentRepositoryPort` guarda el pago local con `createdAt` (vía `ClockPort`) y estado inicial.
3. **`WalletBackendPort`** → adaptador `bcpmock`: llama al mock en `…/groups/1` con timeout configurado y traduce el JSON de respuesta a un objeto de dominio.
4. **`PaymentService`** aplica el estado devuelto y actualiza el pago. Marca el registro de idempotencia como `COMPLETED` con la respuesta guardada. Emite `payment_submitted` y `payment_confirmed` por `AnalyticsEventRepositoryPort`.
5. El controller responde.

**Respuesta**
```http
HTTP/1.1 201 Created
X-Request-Id: 9f3b…

{
  "paymentId": "pay_789",
  "walletId": "wlt_001",
  "amountCents": 1500,
  "currency": "PEN",
  "status": "CONFIRMED",
  "statusReason": null,
  "rail": null,
  "destination": { "type": "QR", "qrId": "qr_123" },
  "createdAt": "2026-11-06T17:16:38Z",
  "updatedAt": "2026-11-06T17:16:39Z",
  "expiresAt": null
}
```
**Reintento** idéntico por pérdida de red: **200/201 con el mismo cuerpo** (el código de la primera respuesta) más `Idempotent-Replayed: true`. El mock **no recibe** una segunda llamada.

### 4.2 Caso de error: clave reutilizada con datos distintos

**Petición** (misma clave del ejemplo anterior, otro monto)
```http
POST /api/v1/payments
Idempotency-Key: 7c1d2e4a-91f0-4c33-8a55-0b6d1e9f2a10

{ "walletId": "wlt_001", "amountCents": 9900, "currency": "PEN",
  "destination": { "type": "QR", "qrId": "qr_123" } }
```

**Recorrido**
1. El controller valida forma: correcto.
2. `PaymentService` calcula el hash del nuevo payload.
3. `IdempotencyRepositoryPort` intenta insertar `(wlt_001, 7c1d…)`. La restricción de unicidad falla, así que lee el registro existente.
4. El hash almacenado ≠ hash recibido, y el registro está `COMPLETED`. El servicio lanza `IdempotencyKeyReusedException` (dominio).
5. **No se llama al mock ni se crea pago alguno.**
6. El manejador de errores del controller mapea la excepción a HTTP 422.

**Respuesta**
```http
HTTP/1.1 422 Unprocessable Entity

{
  "error": {
    "code": "IDEMPOTENCY_KEY_REUSED",
    "message": "La clave de idempotencia ya se usó con datos distintos.",
    "traceId": "c7a1…"
  }
}
```

### 4.3 Variante de error: QR expirado
Si el mock responde `QR_EXPIRED`, el adaptador lo traduce a un error de dominio. El pago queda `EXPIRED` con `statusReason: "QR_EXPIRED"`, la respuesta es **422** `QR_EXPIRED` y se emite `payment_failed`. Reintentar con la misma clave devuelve esta misma respuesta. Para pagar, el usuario debe iniciar una nueva intención con una **nueva** clave.

---

## 5. Criterios de aceptación verificables

Formato Dado/Cuando/Entonces. Cada bloque indica si se cumple completamente, parcialmente o no, y por qué.

### #2 Registro y activación del MVP de wallet: **Cumple parcialmente `[PENDIENTE]`**
- Dado un `documentNumber` `TEST-001`, cuando se llama `POST /onboarding`, entonces responde 201 con `currentStep=KYC_VERIFICATION`.
- Dado un onboarding con KYC aprobado, cuando se llama `…/activation`, entonces la respuesta incluye `walletId` y `status=COMPLETED`.
- Dado un onboarding interrumpido, cuando se llama `GET /onboarding/{id}`, entonces se retoma desde `currentStep`.
- Dado un KYC rechazado, entonces se devuelve 422 `KYC_REJECTED`.
- Cada transición de paso emite el evento analítico correspondiente con marca de tiempo, lo que permite medir el tiempo de onboarding.
- **Limitación:** el objetivo "8 → 3 minutos" **no puede garantizarse con este diseño**. El diseño solo lo hace medible. Depende del diseño de UX y de la latencia del mock.
- **Limitación:** el KYC solo funciona con documentos ficticios `TEST-*`. Un KYC real queda fuera del mock.

### #3 Confirmación de pagos de bajo monto: **Cumple, con dependencias `[PENDIENTE]`**
- Dado un pago válido por debajo del límite, entonces responde 201 con `status` explícito.
- Dado `amountCents` mayor al límite, entonces responde 422 `AMOUNT_EXCEEDS_LIMIT` y no se llama al mock.
- Dado un pago `PENDING`, `GET /payments/{id}` devuelve estado y `updatedAt` actualizados.
- Los valores de límite no están definidos, así que la verificación numérica queda bloqueada hasta que se definan.
- Los objetivos de reducir el abandono en checkout de 30% a 8% **no son verificables por este diseño**. Solo se instrumenta `checkout_started` → `payment_confirmed`.

### #4 Visibilidad del estado de pagos QR para comercios: **Cumple parcialmente**
- Dado `merchant-demo`, cuando se crea un cobro QR y se paga, entonces `GET /merchants/merchant-demo/payments` lo lista con su estado.
- Dado un QR vencido, entonces el pago figura como `EXPIRED` (derivado).
- Dado cualquier otro `merchantId`, entonces 404 `MERCHANT_NOT_FOUND`.
- **Limitación:** solo pagos creados a través del BFF aparecen en el listado. **No hay notificación push** al comercio porque el mock no ofrece webhooks. La visibilidad se logra por consulta, con la latencia del poller.

### #5 Conciliación operativa de transacciones: **Cumple parcialmente**
- Dado un conjunto de pagos, cuando se toma una instantánea, entonces `totals` coincide con los pagos del BFF para la fecha.
- Dada una diferencia entre BFF y mock, entonces aparece en `discrepancies`.
- **Limitación:** no hay corte diario ni canal en el mock, así que la instantánea **no sustituye una conciliación contable oficial**. Un cierre definitivo requiere una fuente que el Issue no provee.

### #6 Idempotencia en pagos: **Cumple, con riesgo residual declarado**
- Dados dos `POST` con la misma clave y payload, entonces existe un solo pago y el segundo devuelve el cuerpo original con `Idempotent-Replayed: true`.
- Dadas dos solicitudes **concurrentes** con la misma clave, entonces una procesa y la otra recibe 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` o el resultado final. Nunca hay dos pagos.
- Dada la misma clave con otro payload, entonces 422 `IDEMPOTENCY_KEY_REUSED`.
- Dado un `POST` sin header, entonces 400 `IDEMPOTENCY_KEY_MISSING`.
- **Riesgo residual:** si el proceso cae después de que el mock aceptó el pago y antes de persistir el resultado, el estado es incierto. La mitigación es `PENDING / UNCONFIRMED_WITH_PROVIDER` más resolución posterior. **Eliminar totalmente el riesgo no es posible** si el mock no ofrece idempotencia o una referencia de correlación propia `[PENDIENTE]`.

### #7 Manejo de fallas de pago y reversos: **Cumple parcialmente**
- Dada una falla del proveedor, entonces el pago queda `FAILED` con `failure.userMessage` y `retryable`.
- Dado un pago `CONFIRMED`, cuando se pide un reverso, entonces 202 y queda registrado.
- Dado un pago `FAILED` o `EXPIRED`, entonces 409 `PAYMENT_NOT_REVERSIBLE`.
- **No cumple:** el flujo de **disputas** no existe en el mock y el diseño no lo cubre. El tratamiento operativo y legal debe definirse fuera de este diseño `[PENDIENTE: Producto/Legal/Operaciones]`.
- **Dependiente del mock:** el reverso automático **solo** si el mock expone esa operación `[PENDIENTE]`.

### #8 Stack técnico y estructura base: **Depende de una decisión pendiente**
- Existe una estructura de proyecto con los paquetes `domain/model`, `domain/port/input`, `domain/port/output`, `application/service`, `infrastructure/adapter/input`, `infrastructure/adapter/output` e `infrastructure/config`.
- Verificable: ningún archivo de `domain/` o `application/` importa de `infrastructure/` (por ejemplo, con una regla de arquitectura automatizada).
- **La elección de lenguaje, framework y base de datos no la define este documento.**

### #9 Cliente del mock y verificación del grupo: **Cumple**
- La URL base del grupo es **configurable** y por defecto es `…/groups/1`. Ningún path del adaptador apunta a la raíz del servicio, que solo es un índice.
- Dada una configuración correcta, `GET /health/upstream` devuelve `reachable=true`.
- Dado un mock caído, devuelve 503.
- El adaptador implementa `WalletBackendPort`, con timeouts y mapeo de errores.
- Hay pruebas del adaptador contra el grupo 1 que **no** ejecutan `POST /control/reset` sin coordinación.

### #10 AGENTS.md y plantilla de Pull Request: **Cumple (artefactos documentales)**
Este diseño no genera esos archivos. Debe incluirlos Backend o DevEx. Contenido mínimo verificable:
- **`AGENTS.md`:** estructura hexagonal y regla de dependencias, convenciones de importes (céntimos de PEN), uso de `Idempotency-Key`, **advertencia sobre `POST /control/reset`** (grupo 1 compartido; coordinar antes de usarlo), cómo ejecutar pruebas y qué marcar como `[PENDIENTE]` en lugar de asumir.
- **Plantilla de PR:** Issue relacionado, criterios de aceptación cubiertos, cambios en contrato de API, supuestos nuevos, confirmación de que no se rompe la regla de dependencias.

### #11 Persistencia propia del BFF: **Cumple, con diseño físico pendiente**
- Existe persistencia para: progreso de onboarding, eventos analíticos, configuración, registro de pagos (`createdAt`), idempotencia, instantáneas de conciliación y solicitudes de reverso.
- Verificable: reiniciar el BFF no pierde el progreso de onboarding ni las claves de idempotencia.
- Las entidades son una **propuesta lógica**. **Los nombres de tablas, tipos y el motor de base de datos los define Datos.**

---

## 6. Supuestos y decisiones pendientes para otros roles

### 6.1 Datos
| # | Tema | Pregunta o propuesta |
|---|---|---|
| D1 | Motor de base de datos y esquema | No definidos en el Issue. Entidades lógicas propuestas: progreso de onboarding, pago local, registro de idempotencia, evento analítico, configuración, instantánea de conciliación, solicitud de reverso. |
| D2 | Unicidad de idempotencia | Se propone `UNIQUE(walletId, idempotencyKey)`. Confirmar. |
| D3 | Unicidad de onboarding | ¿Uno por `documentNumber`? ¿Qué pasa si el anterior fue `REJECTED`? |
| D4 | Retención | Claves de idempotencia (mínimo propuesto 24 h), eventos analíticos y instantáneas. |
| D5 | Unicidad de instantáneas | ¿Una por `businessDate` o múltiples? Este diseño asume múltiples. |
| D6 | Concurrencia | Estrategia ante actualizaciones simultáneas del mismo pago (poller y request): bloqueo optimista con versión, o equivalente. |
| D7 | Zona horaria de `businessDate` | Se propone `America/Lima`. Confirmar. |
| D8 | Datos personales | Qué se guarda de `documentNumber` y `fullName`, cifrado o enmascarado, y su exclusión de los eventos analíticos. Es un requisito legal pendiente. |

### 6.2 Backend
| # | Tema | Pregunta o propuesta |
|---|---|---|
| B1 | **Contrato real del mock** | Verificar en OpenAPI v0.3.0 los paths, esquemas, estados y códigos de error de: wallets, pagos (¿crear y confirmar son dos pasos?), QR (¿con importe y expiración?), ledger, conciliación y soporte. Todo lo marcado `[PENDIENTE: verificar OpenAPI]` depende de esto. |
| B2 | Idempotencia en el mock | ¿Acepta algún header o referencia de correlación? Define cómo resolver pagos de estado incierto tras timeout. |
| B3 | Mapeo de estados | Mapeo oficial de estados y errores del mock a `PENDING/CONFIRMED/FAILED/EXPIRED/REVERSED`. |
| B4 | Timeout de `PENDING` | Valor de `pendingTimeoutSeconds` para derivar `EXPIRED`. |
| B5 | Poller | Frecuencia, y si `/control/events` es apto para uso continuo o solo de control. Cómo evitar procesar el mismo evento dos veces (cursor, id de evento). |
| B6 | Reverso | ¿El mock tiene operación de reverso? Si no, el reverso queda como solicitud registrada. |
| B7 | Autenticación de clientes del BFF | No definida en el Issue. **Bloquea producción** (ver §3). |
| B8 | Límites | Valores numéricos del límite regulatorio y por proveedor/riel. Fuente y forma de actualización. |
| B9 | Stack técnico | Decisión de #8. La estructura de paquetes asume un lenguaje con paquetes/módulos equivalentes. |
| B10 | Uso de `POST /control/reset` | Definir protocolo de coordinación para pruebas con agentes. Propuesta: prohibido en CI sin aviso. |
| B11 | Reintentos hacia el mock | Política (cuántos, con qué espera). **Nunca reintentar automáticamente un pago de estado incierto sin consultar antes.** |

### 6.3 Frontend
| # | Tema | Pregunta o propuesta |
|---|---|---|
| F1 | Pasos de onboarding | Confirmar `KYC_VERIFICATION → WALLET_ACTIVATION → COMPLETED` y los datos a recolectar. El Issue indica que los pasos son del BFF, no del mock. |
| F2 | Clave de idempotencia | Generar un UUID **por intención de pago** y reutilizarlo en todos los reintentos del mismo intento. Nunca regenerarlo en un reintento. |
| F3 | Estados en pantalla | Textos y comportamiento para `PENDING`, `EXPIRED`, `FAILED` y `UNCONFIRMED_WITH_PROVIDER`. Un estado incierto debe mostrarse como "en verificación", nunca como éxito ni como fallo. |
| F4 | Polling | Frecuencia con la que se consulta `GET /payments/{id}` mientras esté `PENDING`. |
| F5 | Eventos del funnel | Confirmar nombres y momentos de `checkout_started` y demás, para calcular abandono. |
| F6 | Pagos al comercio | El comercio verá estados por consulta. No hay notificaciones push. |

### 6.4 Producto, Legal y Operaciones (fuera de los tres roles técnicos, pero bloqueantes)
- **Disputas y reversos (riesgo explícito de la épica):** no hay flujo de disputas ni escritura de soporte en el mock. Se requiere un proceso operativo y legal definido.
- **Métricas de negocio:** MAU (1.000–2.000) puede derivarse de los eventos analíticos. **CAC de PEN 10 no puede calcularse con este diseño**, porque requiere datos de gasto de adquisición que el Issue no incluye. La reducción de abandono y de tiempo de onboarding se hace **medible**, no está **garantizada**.
- **Alias de pago inmediato 24x7x365:** el contrato usa `contactId`/`qrId` porque es lo que el mock conoce (`contact-demo`). El formato y la resolución real de alias son `[PENDIENTE]`.

### 6.5 Resumen de criterios que no se cumplen o se cumplen solo en parte
1. **Disputas (#7):** no cubiertas. El mock no las ofrece.
2. **Conciliación oficial (#5):** solo instantáneas de BFF, no cierre contable.
3. **Notificación push a comercios (#4):** no disponible. Solo consulta.
4. **Duplicidad financiera (#6):** mitigada, no eliminada, en el caso de caída entre llamada al mock y persistencia del resultado.
5. **Métricas de la épica:** abandono, tiempo de onboarding y MAU quedan medibles. CAC no es calculable con este diseño.
6. **Comercios:** solo `merchant-demo` en el mock.