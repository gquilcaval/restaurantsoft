# Programa de impresión de RestOS

Envía las comandas de RestOS a las ticketeras de cocina y barra. Va en **una PC del local** (por ejemplo, la de caja)
conectada a la **misma red** que las impresoras. La PC debe quedarse encendida durante el servicio.

## Requisitos
- Java 17 o superior (gratis: https://adoptium.net).
- Ticketeras en red (cable o Wi-Fi) que acepten ESC/POS por el puerto 9100: Epson, Xprinter, 3nStar, Bixolon y compatibles.

## Instalación
1. Descarga `RestosPrintAgent.java` desde RestOS (Configuración → Impresoras) y guárdalo en una carpeta de la PC.
2. En RestOS: **Configuración → Impresoras → Vincular una PC**. Aparece un código de 6 dígitos (vale 15 minutos).
3. En la PC, abre una ventana de comandos en la carpeta del archivo y ejecuta:
   ```
   java RestosPrintAgent.java vincular http://DIRECCION-DE-RESTOS 123456
   ```
4. Luego, para dejarlo funcionando:
   ```
   java RestosPrintAgent.java
   ```
   Deja esa ventana abierta. En RestOS verás la PC como «Conectada».

## Que arranque solo al encender la PC (Windows)
Crea un archivo `RestOS impresión.bat` con:
```
cd /d C:\ruta\a\la\carpeta
java RestosPrintAgent.java
```
y pon un acceso directo a ese archivo en la carpeta de inicio (`Win + R` → `shell:startup`).

## Si algo falla
- **«La impresora no responde»**: revisa que esté encendida, con papel y conectada a la red, y que la IP en RestOS sea la correcta
  (la IP sale en la hoja de configuración de la impresora: mantén presionado el botón de avance de papel al encenderla).
- RestOS avisa a los meseros si una comanda no salió, y se puede reintentar desde la mesa.
