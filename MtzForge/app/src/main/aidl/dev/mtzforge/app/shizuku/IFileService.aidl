package dev.mtzforge.app.shizuku;

// Servicio que Shizuku ejecuta con el uid de shell (ADB). Solo expone operaciones
// de fichero acotadas y el cierre forzado de la app Temas; no hay ejecución libre de comandos.
interface IFileService {
    // Código reservado por Shizuku para destruir el UserService.
    void destroy() = 16777114;
    int uid() = 1;
    boolean writeFile(String path, in ParcelFileDescriptor source) = 2;
    boolean mkdirs(String path) = 3;
    boolean exists(String path) = 4;
    boolean deleteRecursively(String path) = 5;
    boolean forceStopThemes() = 6;
}
