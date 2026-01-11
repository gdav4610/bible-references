# Despliegue a Azure (ACR + Web App for Containers)

Este documento muestra los pasos para crear recursos en Azure (Resource Group, ACR, App Service Plan y Web App) y desplegar la imagen Docker de la aplicación.

Parámetros usados en este repo (ajusta si lo necesitas):
- Resource Group: `gdResourceGroup`
- ACR: `gdAcr` (login server será `gdacr.azurecr.io`)
- Web App: `BibliaPalabraClave`
- App Service Plan: `gdAppPlan` (SKU F1 — gratuito)

Nota: el `Dockerfile` y el workflow de GitHub Actions han sido actualizados para usar Java 25 (Temurin 25) en el build y runtime.

Requisitos locales:
- Azure CLI instalado
- Docker instalado
- Maven instalado
- JDK 17+ local para compilar con Maven (el runner usa JDK 25); puedes instalar JDK 25 si lo deseas.

Comandos (PowerShell)

1) Login a Azure

```powershell
az login
```

2) Crear Resource Group

```powershell
az group create --name gdResourceGroup --location "West Europe"
```

3) Crear ACR (Azure Container Registry)

```powershell
az acr create --resource-group gdResourceGroup --name gdAcr --sku Basic --admin-enabled true
```

4) Crear App Service Plan (Linux) en SKU F1 (gratuito)

```powershell
az appservice plan create --name gdAppPlan --resource-group gdResourceGroup --is-linux --sku F1
```

Fallback si F1 no se permite para Linux:

```powershell
az appservice plan create --name gdAppPlan --resource-group gdResourceGroup --is-linux --sku B1
```

5) Crear Web App for Containers apuntando a una imagen (se configurará desde el workflow o manualmente)

```powershell
# No configurar imagen ahora; la workflow de GitHub Actions se encargará de apuntar la imagen.
az webapp create --resource-group gdResourceGroup --plan gdAppPlan --name BibliaPalabraClave --runtime "" 
```

6) Configurar WEBSITES_PORT (si la app escucha en 8080)

```powershell
az webapp config appsettings set --name BibliaPalabraClave --resource-group gdResourceGroup --settings WEBSITES_PORT=8080
```

7) Habilitar logs del contenedor y ver logs

```powershell
az webapp log config --name BibliaPalabraClave --resource-group gdResourceGroup --docker-container-logging filesystem
az webapp log tail --name BibliaPalabraClave --resource-group gdResourceGroup
```

8) Configurar Secrets en GitHub

- AZURE_CREDENTIALS (JSON del service principal)
- AZURE_ACR_NAME (ej: gdAcr)
- AZURE_ACR_LOGIN_SERVER (ej: gdacr.azurecr.io)
- AZURE_WEBAPP_NAME (BibliaPalabraClave)
- AZURE_RG (gdResourceGroup)


Workflow de GitHub Actions en `.github/workflows/ci-cd.yml` construirá la imagen con Maven y Docker, la subirá a ACR y actualizará la Web App.

Notas de Hazelcast:
- Hay un bean `HazelcastConfig` que intenta leer `hazelcast.yaml` desde el classpath. Si lo dejas así, Hazelcast correrá embebido en cada instancia del contenedor (no cluster entre instancias en Web App). Si quieres clustering, se recomienda AKS o un servicio gestionado.


Si quieres que ejecute los comandos en tu cuenta Azure (crear los recursos), necesitarás proporcionarme credenciales o ejecutarlos localmente. Recomiendo crear un service principal y añadir su JSON a `AZURE_CREDENTIALS` en GitHub.
