# Revisión final del Chapter Lead: Registro y activación del MVP de wallet

**Historia:** `packages/user-stories/user-onboarding-mvp-completion.sp.md` · **Épica:** #1 · **Tareas:** #12 (BFF), #13 (UI), #14 (Analítica)
**Fecha de revisión:** 2026-11-06

---

## 1. Resumen

**Sobre el feedback humano («aprueba»).** Lo registro, pero no cambia el resultado. Este informe solo puede dar por válido lo que consta en las entregas. Una instrucción de aprobar no sustituye evidencia ni una aprobación formal de riesgos, y mis reglas de rechazo son objetivas. Si Producto o Seguridad quieren aprobar con excepciones, deben dejarlo por escrito: qué criterio se difiere, qué riesgo Alto se acepta y quién lo firma. Hasta entonces el veredicto se mantiene.

**Estado real de la entrega.**
- **Arquitectura:** el contrato llega truncado después de `E1 … 201 Created`. Define un diseño hexagonal **[PROPUESTO]**, con endpoints, eventos y reporte, que no está confirmado contra el repositorio. El contrato de `POST /kyc/verifications` es desconocido.
- **Datos:** no hay entrega. El modelo `onboarding_session` es un supuesto de Backend y no existe el trabajo de #14.
- **Backend:** solo **E1 `POST /onboarding/sessions`**. No hay consulta de progreso, envío de paso (integración KYC), completar/activar wallet, eventos de fin de paso o cierre, ni reporte. El código no sigue el mapa hexagonal propuesto por Arquitectura.
- **Frontend:** es una especificación, no código verificable. `submitStep()` devuelve "no disponible" a propósito. El flujo de avance, completado y activación no es utilizable de extremo a extremo.
- **Pruebas:** todo está **NO EJECUTADO** o **BLOQUEADO**. No hay ninguna salida real adjunta.
- **Seguridad:** hay 3 hallazgos de severidad **Alta** (SEC-01, SEC-02, SEC-03) sin corrección verificable, y varios de severidad Media abiertos.

**Conclusión:** la entrega cubre una fracción del alcance (E1 y el esqueleto de UI). Hay criterios sin cubrir, no hay evidencia de ejecución y hay hallazgos Altos abiertos. **Se rechaza.**

---

## 2. Matriz de criterios

| # | Criterio | Estado | Evidencia |
|---|---|---|---|
| 1 | Secuencia de pasos y paso actual siempre visibles | **Parcial** | Backend: E1 devuelve el modelo de progreso con `steps[]` y `currentStepId`, con catálogo provisional. Frontend: indicador `nav>ol` con `aria-current` y "Paso X de N", solo como spec. Faltan la consulta de progreso para reabrir la app (BL-01 bloqueado) y el código real de la UI. Pruebas AP-01, AP-02 y UI-01/02 están NO EJECUTADAS. |
| 2 | Bloqueo de avance y mensaje accionable ante datos faltantes o inválidos | **Sin cubrir en servidor / parcial en cliente** | Solo se valida `acquisitionChannel` (≤50) en E1. No existe el envío de paso con validación de servidor. El frontend valida en cliente a partir de campos que dependen de SP-4 (no definidos). BL-02 está BLOQUEADO (H-01, H-02). |
| 3 | Wallet activa y acceso al punto de entrada de pagos | **Sin cubrir** | No hay endpoint de completar/activar (Backend). La UI tiene el botón solo en spec y la ruta de pagos es desconocida (H-12). BL-03 está BLOQUEADO. SEC-01 indica que la activación ni siquiera exige KYC aprobado. |
| 4 | Eventos de inicio, fin de cada paso y cierre del flujo | **Parcial** | Solo `onboarding_started` está implementado (Backend, con publicador CDI). Faltan el fin de paso y el cierre (H-04). Los eventos no son observables desde fuera (BL-05). |
| 5 | Mediana de onboarding de 8 a 3 min (cohorte Perú) | **Sin cubrir** | Es un resultado de negocio. Arquitectura solo propone medirlo. Datos no entregó definiciones, consultas ni reporte (H-08). La frontera de eventos del Issue sigue sin cierre formal. |
| 6 | Salida analítica con nuevos usuarios activos mensuales y gasto de adquisición (MAU/CAC) | **Sin cubrir** | No hay entrega de Datos (#14). `AcquisitionSpendPort` y la fuente del gasto no están definidos. No hay reporte. |

**Tareas del Issue:** #12 incompleta (1 de ~4 operaciones). #13 parcial (spec sin código verificado). #14 no iniciada.

---

## 3. Hallazgos

### 3.1 Coherencia entre capas

| ID | Sev. | Ubicación | Hallazgo | Corrección |
|---|---|---|---|---|
| CH-01 | **Alta** | Contrato de Arquitectura ↔ Backend ↔ Frontend | Hay un contrato incompleto y sin cerrar. Backend y Frontend trabajaron con el contrato truncado después de `E1 … 201 Created`. Faltan por definir y entregar GET de progreso, envío de paso, completar y reporte. El `200` en reanudación y el `400`/`401` de E1 provienen de Backend, no del contrato de Arquitectura visible (H-09). | **Arquitectura:** publicar el contrato completo (rutas, métodos, cuerpos, códigos de error y valores de `code`). **Backend/Frontend:** reconciliar contra él. |
| CH-02 | **Alta** | `POST /kyc/verifications` | El contrato de la dependencia (body, respuestas, errores, autenticación) es desconocido, por lo que la integración es imposible de implementar o verificar (H-02). | **Arquitectura/Backend:** documentar el contrato real desde el repositorio. Definir el mapeo KYC aprobado/rechazado/pendiente → estado del paso. |
| CH-03 | Media | `currentStepIndex` | Es ambiguo entre base 0 y base 1. El ejemplo del contrato muestra `2` para `IDENTITY_DOCUMENT` (orden 2). Backend habla de "paso 1" (H-03). Frontend lo evita y usa `steps[].status`, por lo que Backend y Frontend no comparten la misma fuente. | **Arquitectura:** fijar la base del índice. **Backend:** documentarlo en el assembler. **Frontend:** consumir una sola fuente. |
| CH-04 | Media | `ErrorResponse` / códigos HTTP | El cuerpo de error `{code, message, correlationId, fieldErrors}` lo define Backend, no el contrato. Frontend mapea por HTTP porque `code` no está especificado (H-06). Frontend define un `409` y un `404` que Backend aún no emite. Backend usa 400/401/500 y el mapper de `DuplicateSessionException` no consta. | **Arquitectura:** tabla oficial de errores y valores de `code`. **Backend:** alinear los mappers. |
| CH-05 | Media | Modelo de datos | No hay entrega de Datos. Tabla, columnas e índice único parcial `(user_id) WHERE status='IN_PROGRESS'` son un supuesto del repositorio JDBC (H-02/H-07 del informe de pruebas). No hay migración. | **Datos:** entregar DDL/migración aprobada. **Backend:** alinearse. |
| CH-06 | Media | Autenticación / `acquisitionChannel` | El frontend recibe `acquisitionChannel` por `@Input` sin origen definido (SP-3). El servidor lo toma del cliente (ver SEC-06). | **Producto/Arquitectura:** definir su origen y una lista cerrada. |

### 3.2 Calidad de código

| ID | Sev. | Ubicación | Hallazgo | Corrección |
|---|---|---|---|---|
| CQ-01 | **Media** | Paquetes `pe.bcp.login.{resource,service,repository,dto,model}` | El backend usa una arquitectura por capas clásica. Arquitectura exige hexagonal (`domain/port/input|output`, `application/service`, `infrastructure/adapter`). `OnboardingService` depende directamente del repositorio JDBC y de `OnboardingEventPublisher`. No hay puertos y las reglas de dominio no están aisladas. No se verificó que `pe.bcp.login` sea el paquete correcto del proyecto (H-11). | **Backend:** extraer puertos de salida (`OnboardingSessionRepositoryPort`, `OnboardingEventPublisherPort`), mover la lógica a `domain/application` e implementar adaptadores en `infrastructure`. Confirmar el paquete base. |
| CQ-02 | Baja | `OnboardingValidationException.getFieldErrors()` | Devuelve el mapa mutable interno, aunque el constructor sí copia defensivamente (H-10). | Devolver `Collections.unmodifiableMap(...)`. |
| CQ-03 | Baja | `StartOnboardingRequest` | Usa campo público en una clase. Aceptable en Quarkus/Jackson, pero inconsistente con los `record` del resto. | Valorar un `record` con `@Size`. |
| CQ-04 | Media | `OnboardingStepCatalog` | El catálogo es código del servicio, mientras que Arquitectura lo pide como configuración (`infrastructure/config`) porque los requisitos de KYC están pendientes. | **Backend:** externalizar a `@ConfigMapping`/propiedades. |
| CQ-05 | Media | Frontend `OnboardingService.submitStep()` | Es un stub que devuelve error "no disponible". El token `ONBOARDING_STEP_FIELDS` va vacío por defecto. El flujo no es utilizable. | **Frontend:** implementar contra el contrato (tras CH-01/CH-02). |
| CQ-06 | Baja | Evidencia de revisión | No puedo revisar código no visible (repositorio truncado, resource y servicio solo por nombre, componente Angular no visible). No emito juicio sobre eso. | Adjuntar el código completo o el diff/PR. |

### 3.3 Pruebas

| ID | Sev. | Hallazgo | Corrección |
|---|---|---|---|
| T-01 | **Alta** | No hay ninguna salida real de ejecución. Todos los casos están NO EJECUTADO. No consta que compile, que pase una prueba ni que haya cobertura. | **QA/Backend/Frontend:** ejecutar y adjuntar la salida real de los comandos de build y de pruebas de cada capa. Los comandos exactos no están visibles en lo que recibí (el informe de pruebas llega truncado). Deben figurar con el comando literal y su salida (por ejemplo, Maven/Gradle para Quarkus y `ng test`/`ng lint`/`ng build` para Angular). |
| T-02 | **Alta** | Casos BLOQUEADOS para C2 (servidor), C3, C4 (fin de paso y cierre), C5 y C6. Los criterios 2, 3, 5 y 6 carecen de prueba escrita. | Desbloquear tras entregar contrato, endpoints y Datos. |
| T-03 | Media | Las pruebas de persistencia dependen de un esquema no aprobado. Las de Idempotency-Key solo comprueban `sessionId` y 200/201 (H-05). | Definir la semántica de idempotencia y probar la reproducción. |
| T-04 | Media | Faltan casos límite críticos por no existir la funcionalidad: concurrencia (`version`), carrera de creación duplicada, reintentos, `CLOSED_INCOMPLETE` y activación con KYC rechazado. | Añadirlos en cuanto existan los endpoints. |
| T-05 | Baja | El contraste de color y la accesibilidad con lector de pantalla no se midieron (NO EJECUTADO). | Medir con herramienta y adjuntar el resultado. |

### 3.4 Seguridad

| ID | Sev. | Estado | Hallazgo | Corrección |
|---|---|---|---|---|
| SEC-01 | **Alta** | **ABIERTO** | La activación de wallet no exige KYC aprobado y persistido en servidor. | Regla de dominio `kycResult == APPROVED` leído del servidor. Rechazar `walletStatus`, `status` y `version` en cualquier body. Añadirlo al criterio 3 y probarlo con KYC rechazado/pendiente. |
| SEC-02 | **Alta** | **ABIERTO** | No hay control de acceso a nivel de objeto (IDOR/BOLA) en los endpoints pendientes que reciban `sessionId`. | `session.userId == token.userId`, respondiendo 404 si no coincide. Incluirlo en el contrato y probarlo con dos usuarios. |
| SEC-03 | **Alta** | **ABIERTO** | No están definidos persistencia, cifrado, retención y consentimiento de datos KYC/PII. | El BFF no persiste documento ni datos KYC, solo referencia y resultado. Definir los requisitos con Cumplimiento/Legal. |
| SEC-04 | Media | Abierto | Mecanismo de autenticación supuesto y no verificado (firma, `exp`, `iss`, `aud`). Pasaría a Crítico si se confirma mal. | Confirmar el mecanismo y validar con la librería estándar. |
| SEC-05 | Media | Abierto | No hay rate limiting en E1 ni en el envío de paso (costo de KYC y abuso). | 429 con `Retry-After` y límite de intentos por sesión. |
| SEC-06 | Media | Abierto | `acquisitionChannel` es texto libre controlado por el cliente. Puede falsear el CAC y provocar XSS o CSV injection en el reporte. | Lista cerrada o patrón `^[a-z0-9_-]{1,50}$`. Escapar en la salida. |
| SEC-07 en adelante | n/d | **No verificable** | El informe de seguridad llega truncado en SEC-07. No puedo confirmar su estado. | **Seguridad:** reenviar el informe completo. |

Los controles positivos observados (`userId` tomado del token, consultas parametrizadas, errores genéricos, `X-Correlation-Id` saneado, rutas de navegación locales, sin `innerHTML` ni almacenamiento de tokens) constan **solo por declaración**. No hay prueba ejecutada que los respalde.

### 3.5 Mantenibilidad y operación

| ID | Sev. | Hallazgo | Corrección |
|---|---|---|---|
| M-01 | Media | **Deuda técnica:** capas no hexagonales, catálogo en código, stub en frontend, tabla supuesta sin migración. | Ver CQ-01, CQ-04, CQ-05 y CH-05. |
| M-02 | Media | **Riesgo de despliegue:** E1 sola no es utilizable. Desplegarla sin el resto crearía sesiones sin forma de completarlas ni de activar la wallet. | Integrar solo cuando exista el flujo completo, o protegerlo con feature flag. |
| M-03 | Media | **Operación:** no se define retención de sesiones abandonadas (`CLOSED_INCOMPLETE`) ni observabilidad (métricas, alertas) más allá del correlation-id. | Definir política de expiración y métricas. |
| M-04 | Baja | **Dependencias:** no hay `pom.xml`/`build.gradle`/`package.json` visibles. No puedo evaluar dependencias innecesarias ni versiones. | Adjuntar manifiestos. |
| M-05 | Baja | **Analítica:** la frontera de eventos que define la duración del onboarding sigue abierta. Arquitectura propone timestamps de servidor sin confirmación de Producto. | Producto debe cerrar la definición de inicio y fin. |

---

## 4. Pendientes antes de integrar

1. Contrato de Arquitectura completo y sin truncar (CH-01), junto con el contrato real de `POST /kyc/verifications` (CH-02).
2. Entrega de Datos: modelo, migración, definiciones de mediana, MAU y CAC, y fuente del gasto de adquisición.
3. Endpoints de Backend: progreso, envío de paso con validación de servidor, completar/activar y reporte. También los eventos de fin de paso y cierre.
4. Resolución verificable de SEC-01, SEC-02 y SEC-03, y respuesta a SEC-04/05/06 y a los hallazgos truncados.
5. Salidas reales de compilación, pruebas, lint y build adjuntas, con los comandos exactos (T-01).
6. Reajuste del backend a la arquitectura hexagonal acordada, o aceptación formal por escrito de la desviación.
7. Código real del frontend integrado con el contrato final, con el stub `submitStep()` sustituido.
8. Decisión de Producto/Cumplimiento sobre requisitos KYC, la frontera de eventos y el origen de `acquisitionChannel`.

---

## 5. Correcciones necesarias por rol

- **Arquitecto:** contrato completo con tabla de errores y `code`. Aclarar la base de `currentStepIndex`. Añadir "wallet solo con KYC aprobado" al criterio 3. Definir idempotencia, límites de uso y control de acceso por objeto. Documentar el contrato real de `/kyc/verifications` y el mecanismo de autenticación.
- **Datos:** DDL y migración de `onboarding_session` con el índice único parcial. Consultas y salida para mediana, MAU y CAC. Fuente del gasto de adquisición y política de retención.
- **Backend:** endpoints faltantes con ownership check y regla KYC=APPROVED. Eventos de fin de paso y cierre. Refactor a puertos y adaptadores. Catálogo en configuración. Rate limiting. Validación estricta de `acquisitionChannel`. No persistir PII de KYC. Copia inmutable en `getFieldErrors()`.
- **Frontend:** implementar `submitStep` y la consulta de progreso contra el contrato. Alinear el mapeo de errores con los códigos reales. Entregar el código verificable.
- **Pruebas:** ejecutar y adjuntar salidas reales. Desbloquear BL-01…BL-07. Añadir casos de concurrencia, activación con KYC rechazado, IDOR y rate limit.
- **Seguridad:** reenviar el informe completo. Revalidar SEC-01…SEC-06 con evidencia verificable tras las correcciones.
- **Producto/Cumplimiento:** requisitos KYC, frontera de duración y, si se desea, una aprobación con excepciones firmada explícitamente.

---

VEREDICTO: RECHAZADO