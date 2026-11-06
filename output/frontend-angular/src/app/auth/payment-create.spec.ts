npm i -D @playwright/test
npx playwright install chromium
npx ng serve --port 4200 &        # o el comando de arranque real del proyecto
FE_BASE_URL=http://localhost:4200 npx playwright test e2e/payment-create.spec.ts --reporter=list