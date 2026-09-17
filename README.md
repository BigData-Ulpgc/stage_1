# Search Engine Project - Stage 1: Building the Data Layer[cite: 1]

Este repositorio contiene la implementación de la **Capa de Datos (Data Layer)** para el proyecto de motor de búsqueda de la asignatura de Big Data (ULPGC). 

El objetivo de esta fase es construir un flujo de trabajo para recopilar, limpiar y organizar libros del *Project Gutenberg*, preparándolos para futuras etapas de indexación[cite: 1].

## 👥 Miembros del Grupo
* **Nombre del Grupo:** [Introduce el nombre de tu grupo]
* **Miembros:**
  * Daniel Rodriguez Alonso - Implementación en Java
  * Daniel Perdomo Medina - Implementación en Python
  * Carlos Falcon Brito - Implementación en C

## 📐 Contrato común entre lenguajes
Las reglas que deben cumplir las tres implementaciones (separación header/body, estructuras del datalake, tokenizador, formato del índice, consultas y formato CSV de los benchmarks) están en [`shared/SPEC.md`](shared/SPEC.md).

## 🏗 Arquitectura del Sistema
El proyecto implementa las siguientes capas[cite: 1]:
* **Datalake:** Almacenamiento estructurado de los libros descargados (separados en cabecera y cuerpo) organizados por fecha y hora[cite: 1].
* **Datamarts:** Almacenamiento optimizado que contiene los metadatos (ej. SQLite) y el Índice Invertido (Inverted Index)[cite: 1].
* **Capa de Control (Control Layer):** Archivos de estado que evitan descargar o indexar libros duplicados[cite: 1].

## 📂 Dataset de Muestra (Sample Dataset)
Se incluye una carpeta llamada `sample_dataset/` con una muestra de libros procesados para que los profesores puedan probar la ejecución del pipeline rápidamente sin necesidad de descargar grandes volúmenes de datos[cite: 1].

## 🚀 Instrucciones de Configuración y Ejecución[cite: 1]
Este proyecto incluye implementaciones en tres lenguajes diferentes para realizar los benchmarks requeridos[cite: 1]. A continuación se explica cómo ejecutar cada uno de ellos:

### ☕ Ejecución en Java
1. Requisitos: **Java 17+** (Maven no hace falta: se incluye el wrapper `mvnw`).
2. Navega al directorio de Java: `cd java/`
3. Compila y pasa los tests: `./mvnw package`
4. Ejecuta el pipeline: `java -jar target/stage1.jar pipeline 10`

Más detalles (comandos, estructura del código, benchmarks) en [`java/README.md`](java/README.md).

### 🐍 Ejecución en Python
1. Asegúrate de tener instalado **Python 3.8+**.
2. Navega al directorio de Python: `cd python/`
3. Instala las dependencias: `pip install -r requirements.txt` (si usáis librerías externas).
4. Ejecuta el pipeline: `python src/main.py`

### ⚙️ Ejecución en C
1. Asegúrate de tener instalado **GCC** y **Make**.
2. Navega al directorio de C: `cd c/`
3. Compila el proyecto ejecutando: `make`
4. Ejecuta el binario generado: `./search_engine_stage1`

## 📊 Benchmarks
En el informe en PDF adjunto en el campus virtual se detallan las métricas de rendimiento (throughput de descarga, velocidad de indexación y tiempos de consulta) comparando las tres implementaciones y las distintas estructuras de almacenamiento[cite: 1].