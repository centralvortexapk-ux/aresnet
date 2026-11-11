# AresNet P2P Tracker & Bootstrap Node

Servidor rastreador ligero para la red P2P descentralizada **AresNet**.

## ¿Qué hace este servidor?
- **NO almacena archivos, música ni videos.**
- Actúa exclusivamente como un directorio temporal en memoria RAM para intercambiar las direcciones IP y puertos públicos entre dispositivos que no están en la misma red Wi-Fi.
- Limpia automáticamente los nodos que se desconectan después de 90 segundos.
- Filtra listas negras de hashes bloqueados (DMCA/Moderación).

---

## Despliegue Gratuito en 3 Pasos (Render.com)

1. Sube este repositorio a tu cuenta de **GitHub**.
2. Entra a [https://render.com](https://render.com) y crea un **New Web Service**.
3. Selecciona tu repositorio y configura:
   - **Root Directory**: `tracker`
   - **Environment**: `Node`
   - **Build Command**: `npm install`
   - **Start Command**: `node server.js`
   - **Plan**: `Free` ($0/mes)
4. ¡Listo! Render te dará una URL HTTPS como: `https://aresnet-tracker.onrender.com`.
5. Pega esa URL en la app de AresNet en **Configuración > Servidor Tracker**.

---

## Ejecución Local con Docker o Node.js

```bash
cd tracker
npm install
npm start
```
El servidor escuchará en `http://localhost:3000`.
Verifica el estado en `http://localhost:3000/health`.
