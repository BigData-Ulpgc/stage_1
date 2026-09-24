package es.ulpgc.bigdata.datamart.metadata;

/**
 * Error del almacén de metadatos. Envuelve la SQLException para que quien usa
 * el repositorio no tenga que importar nada de JDBC.
 */
public class MetadataRepositoryException extends RuntimeException {

    public MetadataRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}