package dev.mtzforge.core

import java.io.File
import kotlin.system.exitProcess

/**
 * CLI de escritorio (sin vistas previas renderizadas):
 *   font  <entrada.ttf|otf|ttc> <salida.mtz> [titulo]
 *   icons <appfilter.xml> <carpeta_png> <salida.mtz> [titulo]
 *   inspect <fichero.mtz>
 */
fun main(args: Array<String>) {
    try {
        run(args)
    } catch (e: Exception) {
        System.err.println("ERROR: ${e.message}")
        exitProcess(2)
    }
}

private fun run(args: Array<String>) {
    when (args.getOrNull(0)) {
        "font" -> {
            if (args.size < 3) usage()
            val data = File(args[1]).readBytes()
            val (_, font) = FontMtzBuilder.prepareFont(data)
            val desc = ThemeDescription(title = args.getOrNull(3) ?: font.displayName)
            val r = File(args[2]).outputStream().use { FontMtzBuilder.build(data, FontMtzOptions(desc), Previews.NONE, it) }
            println("OK: ${r.font.displayName} -> ${args[2]}"); r.warnings.forEach { println("AVISO: $it") }
        }
        "icons" -> {
            if (args.size < 4) usage()
            val filter = AppFilter.parseXml(File(args[1]).readBytes())
            val dir = File(args[2])
            val desc = ThemeDescription(title = args.getOrNull(4) ?: "Iconos")
            val r = File(args[3]).outputStream().use {
                IconMtzBuilder.build(filter, IconMtzOptions(desc), Previews.NONE, it) { d -> File(dir, "$d.png").takeIf(File::isFile)?.readBytes() }
            }
            println("OK: ${r.written} iconos; sin imagen: ${r.missing.size}")
        }
        "inspect" -> {
            if (args.size < 2) usage()
            val rep = MtzInspector.inspect(File(args[1]))
            println("Título: ${rep.description?.title}  uiVersion: ${rep.description?.uiVersion}")
            println("Componentes: ${rep.components}")
            rep.fontName?.let { println("Fuente: $it") }
            if (rep.iconCount > 0) println("Iconos: ${rep.iconCount}")
            rep.warnings.forEach { println("AVISO: $it") }
            rep.errors.forEach { println("ERROR: $it") }
            println(if (rep.isValid) "VÁLIDO" else "NO VÁLIDO")
            if (!rep.isValid) exitProcess(2)
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("Uso: font <in> <out.mtz> [titulo] | icons <appfilter.xml> <dir_png> <out.mtz> [titulo] | inspect <f.mtz>")
    exitProcess(1)
}
