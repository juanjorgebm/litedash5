package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.RectF
import java.util.Random
import kotlin.math.min

class Question(val subject: String, val text: String, val options: List<String>, val correct: Int)

/** Operaciones cuyo resultado tiene 2 cifras (10 a 99). Nivel 0: + -, 1: + - ×, 2: + - × ÷. */
object Mate {
    fun gen(level: Int, rnd: Random): Question {
        val ops = when (level) { 0 -> "+-"; 1 -> "+-×"; else -> "+-×÷" }
        val op = ops[rnd.nextInt(ops.length)]
        var a: Int; var b: Int; var r: Int
        when (op) {
            '+' -> { r = 10 + rnd.nextInt(90); a = 2 + rnd.nextInt(r - 3); b = r - a }
            '-' -> { r = 10 + rnd.nextInt(80); b = 1 + rnd.nextInt(99 - r); a = r + b }
            '×' -> { do { a = 2 + rnd.nextInt(11); b = 2 + rnd.nextInt(11); r = a * b } while (r !in 10..99) }
            else -> { do { b = 2 + rnd.nextInt(8); r = 10 + rnd.nextInt(90); a = r * b } while (a > 150) }
        }
        val shown = if (op == '-') "−" else op.toString()
        val opts = LinkedHashSet<Int>(); opts.add(r)
        val near = mutableListOf(r + 1, r - 1, r + 2, r - 2, r + 10, r - 10, r + 11, r - 9, (r % 10) * 10 + r / 10)
        near.shuffle(rnd)
        for (n in near) { if (opts.size >= 4) break; if (n in 10..99) opts.add(n) }
        while (opts.size < 4) opts.add(10 + rnd.nextInt(90))
        val list = opts.map { it.toString() }.shuffled(rnd)
        return Question("", "$a $shown $b = ?", list, list.indexOf(r.toString()))
    }
}

/** Preguntas escolares para unos 10 años. La primera opción de cada una es la correcta. */
object Trivia {
    val SUBJECTS = listOf("Matemáticas", "Español", "Ciencias", "Estudios Sociales")
    private class Q(val s: Int, val band: Int, val text: String, vararg val opts: String)

    private val BANK = listOf(
        // Matemáticas
        Q(0, 1, "¿Cuántos lados tiene un hexágono?", "6", "5", "8", "7"),
        Q(0, 1, "¿Cuántos minutos tiene una hora?", "60", "100", "30", "50"),
        Q(0, 1, "¿Cuál es el doble de 25?", "50", "52", "40", "75"),
        Q(0, 1, "¿Cuál es la mitad de 90?", "45", "40", "35", "55"),
        Q(0, 1, "¿Cuántos centímetros tiene un metro?", "100", "10", "1000", "50"),
        Q(0, 2, "¿Cuál de estos números es primo?", "7", "9", "15", "21"),
        Q(0, 2, "¿Cuántos grados mide un ángulo recto?", "90°", "45°", "180°", "100°"),
        Q(0, 2, "¿Cómo se llama el resultado de una multiplicación?", "Producto", "Suma", "Cociente", "Diferencia"),
        Q(0, 2, "¿Cómo se llama el resultado de una división?", "Cociente", "Producto", "Resta", "Factor"),
        Q(0, 1, "¿Cuántos días tiene un año que no es bisiesto?", "365", "366", "360", "350"),
        Q(0, 1, "¿Qué fracción es igual a un medio?", "2/4", "1/3", "3/4", "2/5"),
        Q(0, 1, "¿Cuántos gramos hay en un kilogramo?", "1000", "100", "10", "500"),
        Q(0, 1, "¿Qué número romano representa el 10?", "X", "V", "L", "C"),
        Q(0, 2, "¿Qué número romano representa el 50?", "L", "C", "D", "X"),
        Q(0, 2, "¿Cuál es el perímetro de un cuadrado de 5 cm de lado?", "20 cm", "25 cm", "10 cm", "15 cm"),
        Q(0, 2, "¿Cuál es el área de un rectángulo de 4 cm por 3 cm?", "12 cm²", "7 cm²", "14 cm²", "24 cm²"),
        Q(0, 1, "¿Cuántas caras tiene un cubo?", "6", "4", "8", "12"),
        Q(0, 2, "¿Cuántas aristas tiene un cubo?", "12", "6", "8", "10"),
        Q(0, 1, "¿Qué número va después de 999?", "1000", "9999", "1001", "990"),
        Q(0, 1, "¿Cuánto es 7 × 8?", "56", "54", "48", "63"),
        Q(0, 1, "¿Cuántas decenas hay en 100?", "10", "100", "1", "20"),
        Q(0, 2, "¿Cómo se llama el triángulo que tiene sus tres lados iguales?", "Equilátero", "Isósceles", "Escaleno", "Rectángulo"),
        Q(0, 2, "¿Cuál de estos números es el mayor?", "0,9", "0,45", "0,09", "0,5"),
        Q(0, 1, "Si tengo 3 docenas de huevos, ¿cuántos huevos tengo?", "36", "30", "33", "24"),
        Q(0, 1, "¿Cuántos segundos tiene un minuto?", "60", "100", "30", "24"),
        Q(0, 2, "¿Qué figura tiene 8 lados?", "Octágono", "Hexágono", "Pentágono", "Decágono"),
        Q(0, 1, "¿Cuánto es 100 − 37?", "63", "73", "67", "53"),
        Q(0, 2, "¿Cuántos años tiene un siglo?", "100", "10", "1000", "50"),
        Q(0, 1, "¿Cuánto vale el 5 en el número 352?", "50", "5", "500", "35"),
        Q(0, 1, "¿Cuánto es 9 × 6?", "54", "56", "45", "63"),
        // Español
        Q(1, 1, "¿Cuál de estas palabras es un sustantivo?", "Mesa", "Correr", "Bonito", "Rápidamente"),
        Q(1, 1, "¿Cuál de estas palabras es un verbo?", "Saltar", "Casa", "Azul", "Feliz"),
        Q(1, 1, "¿Cuál de estas palabras es un adjetivo?", "Alegre", "Perro", "Cantar", "Ayer"),
        Q(1, 1, "¿Cuál es un sinónimo de «feliz»?", "Contento", "Triste", "Enojado", "Cansado"),
        Q(1, 1, "¿Cuál es el antónimo de «grande»?", "Pequeño", "Enorme", "Alto", "Ancho"),
        Q(1, 1, "¿Cuál es el plural de «lápiz»?", "Lápices", "Lápizes", "Lápiz", "Lápizs"),
        Q(1, 1, "¿Cuántas sílabas tiene la palabra «mariposa»?", "4", "3", "5", "2"),
        Q(1, 2, "¿Cuál de estas palabras es aguda?", "Canción", "Árbol", "Mesa", "Música"),
        Q(1, 2, "¿Cuál de estas palabras es esdrújula?", "Pájaro", "Camión", "Lápiz", "Ventana"),
        Q(1, 2, "¿Cuál de estas palabras es grave (llana)?", "Árbol", "Café", "Sofá", "Película"),
        Q(1, 1, "¿Cuál palabra está bien escrita?", "Hacer", "Aser", "Haser", "Hacher"),
        Q(1, 1, "¿Cuál palabra está bien escrita?", "Zapato", "Sapato", "Zapatto", "Sapatto"),
        Q(1, 1, "¿Qué signos se usan para hacer una pregunta?", "¿ ?", "¡ !", "« »", "( )"),
        Q(1, 1, "¿Cómo se llama la persona que escribe poemas?", "Poeta", "Pintor", "Escultor", "Actor"),
        Q(1, 2, "En «El perro come», ¿cuál es el sujeto?", "El perro", "Come", "El", "No tiene"),
        Q(1, 2, "¿Cuál de estas palabras es un artículo?", "La", "Sol", "Muy", "Correr"),
        Q(1, 0, "¿Cuántas vocales tiene el español?", "5", "6", "7", "4"),
        Q(1, 0, "¿Qué letra va después de la «n» en el abecedario?", "Ñ", "O", "M", "P"),
        Q(1, 0, "¿Cuál es el diminutivo de «casa»?", "Casita", "Casona", "Caserío", "Casero"),
        Q(1, 1, "¿Cuál es el aumentativo de «perro»?", "Perrote", "Perrito", "Perrera", "Perruno"),
        Q(1, 2, "¿Cuál de estas palabras es un pronombre?", "Nosotros", "Mesa", "Comer", "Verde"),
        Q(1, 1, "¿Cuál es el femenino de «actor»?", "Actriz", "Actora", "Actriza", "Actres"),
        Q(1, 2, "¿Qué tipo de texto es una receta de cocina?", "Instructivo", "Poema", "Cuento", "Leyenda"),
        Q(1, 2, "¿Cómo se llama una historia tradicional de un lugar con hechos fantásticos?", "Leyenda", "Noticia", "Receta", "Carta"),
        Q(1, 1, "¿Cuál es un sinónimo de «rápido»?", "Veloz", "Lento", "Pesado", "Tranquilo"),
        Q(1, 0, "¿Cuál es el antónimo de «día»?", "Noche", "Tarde", "Sol", "Mañana"),
        Q(1, 2, "¿Cuál de estas palabras necesita tilde?", "cancion", "mesa", "perro", "amor"),
        Q(1, 1, "¿Cuál es el plural de «pez»?", "Peces", "Pezes", "Pezs", "Pez"),
        Q(1, 0, "¿Qué palabra rima con «ratón»?", "Melón", "Gato", "Queso", "Raro"),
        Q(1, 2, "¿Cuál de estas palabras es un adverbio?", "Lentamente", "Lento", "Lentitud", "Lentes"),
        // Ciencias
        Q(2, 1, "¿Qué planeta está más cerca del Sol?", "Mercurio", "Venus", "Tierra", "Marte"),
        Q(2, 1, "¿Cuál es el planeta más grande del sistema solar?", "Júpiter", "Saturno", "Tierra", "Neptuno"),
        Q(2, 1, "¿Qué gas necesitamos respirar para vivir?", "Oxígeno", "Dióxido de carbono", "Nitrógeno", "Helio"),
        Q(2, 2, "¿Qué gas toman las plantas del aire para la fotosíntesis?", "Dióxido de carbono", "Oxígeno", "Helio", "Hidrógeno"),
        Q(2, 2, "¿Cuántos huesos tiene el cuerpo de un adulto?", "206", "100", "350", "52"),
        Q(2, 1, "¿Qué órgano bombea la sangre por el cuerpo?", "El corazón", "Los pulmones", "El hígado", "El estómago"),
        Q(2, 1, "¿Con qué órganos respiramos?", "Los pulmones", "El corazón", "Los riñones", "El cerebro"),
        Q(2, 1, "¿Cuáles son los tres estados del agua?", "Sólido, líquido y gaseoso", "Frío, tibio y caliente", "Hielo, nieve y lluvia", "Duro, blando y suave"),
        Q(2, 1, "¿A qué temperatura hierve el agua al nivel del mar?", "100 °C", "50 °C", "0 °C", "200 °C"),
        Q(2, 1, "¿A qué temperatura se congela el agua?", "0 °C", "10 °C", "100 °C", "-10 °C"),
        Q(2, 1, "¿Cómo se llaman los animales que solo comen plantas?", "Herbívoros", "Carnívoros", "Omnívoros", "Insectívoros"),
        Q(2, 2, "¿Cómo se llaman los animales que comen plantas y carne?", "Omnívoros", "Herbívoros", "Carnívoros", "Frugívoros"),
        Q(2, 1, "¿Cuál de estos animales es un mamífero?", "La ballena", "El tiburón", "La tortuga", "El pingüino"),
        Q(2, 1, "¿Cuál de estos animales es un anfibio?", "La rana", "La serpiente", "El cocodrilo", "La lagartija"),
        Q(2, 1, "¿Cuál de estos animales es un reptil?", "La iguana", "La rana", "El delfín", "El murciélago"),
        Q(2, 1, "¿Qué parte de la planta absorbe el agua del suelo?", "La raíz", "La flor", "La hoja", "El fruto"),
        Q(2, 2, "¿Cómo se llama el proceso con el que las plantas fabrican su alimento?", "Fotosíntesis", "Digestión", "Respiración", "Evaporación"),
        Q(2, 0, "¿Cuál es el satélite natural de la Tierra?", "La Luna", "El Sol", "Marte", "Venus"),
        Q(2, 2, "¿Cuánto tarda la Tierra en dar una vuelta alrededor del Sol?", "Un año", "Un día", "Un mes", "Una semana"),
        Q(2, 1, "¿Cuánto tarda la Tierra en girar sobre sí misma?", "Un día", "Un año", "Una hora", "Un mes"),
        Q(2, 0, "¿Qué sentido usamos con la nariz?", "El olfato", "El gusto", "El tacto", "El oído"),
        Q(2, 1, "¿Cuál es el órgano más grande del cuerpo humano?", "La piel", "El corazón", "El cerebro", "El hígado"),
        Q(2, 2, "¿Cómo se llama la energía que se obtiene del viento?", "Eólica", "Solar", "Hidráulica", "Geotérmica"),
        Q(2, 1, "¿Cómo se llama la energía que se obtiene del Sol?", "Solar", "Eólica", "Hidráulica", "Geotérmica"),
        Q(2, 2, "¿Cómo se llama el paso del agua de líquido a gas?", "Evaporación", "Congelación", "Condensación", "Fusión"),
        Q(2, 0, "¿Qué insecto produce la miel?", "La abeja", "La mosca", "La hormiga", "La mariposa"),
        Q(2, 1, "¿Cuántas patas tiene un insecto?", "6", "8", "4", "10"),
        Q(2, 1, "¿Cuántas patas tiene una araña?", "8", "6", "10", "4"),
        Q(2, 2, "¿Cómo se llama la etapa en que la oruga se envuelve antes de ser mariposa?", "Crisálida", "Renacuajo", "Polluelo", "Semilla"),
        Q(2, 1, "¿Qué órgano controla todo el cuerpo y nos permite pensar?", "El cerebro", "El corazón", "El estómago", "Los riñones"),
        // Estudios Sociales
        Q(3, 0, "¿Cuál es la capital de Costa Rica?", "San José", "Alajuela", "Cartago", "Heredia"),
        Q(3, 1, "¿Cuántas provincias tiene Costa Rica?", "7", "5", "6", "9"),
        Q(3, 1, "¿Qué día se celebra la independencia de Costa Rica?", "15 de setiembre", "25 de julio", "12 de octubre", "1 de mayo"),
        Q(3, 2, "¿En qué año se independizó Costa Rica?", "1821", "1810", "1856", "1948"),
        Q(3, 1, "¿Cuál es la flor nacional de Costa Rica?", "La guaria morada", "La rosa", "El girasol", "El lirio"),
        Q(3, 1, "¿Cuál es el árbol nacional de Costa Rica?", "El guanacaste", "El roble", "El ceibo", "El pino"),
        Q(3, 1, "¿Cuál es el ave nacional de Costa Rica?", "El yigüirro", "El quetzal", "La lapa roja", "El tucán"),
        Q(3, 1, "¿Qué día se celebra la anexión del Partido de Nicoya?", "25 de julio", "15 de setiembre", "11 de abril", "2 de agosto"),
        Q(3, 2, "¿Qué héroe nacional quemó el mesón en la Batalla de Rivas?", "Juan Santamaría", "Juan Rafael Mora", "Francisco Morazán", "José Figueres"),
        Q(3, 2, "¿Qué batalla se recuerda en Costa Rica el 11 de abril?", "La Batalla de Rivas", "La Batalla de Santa Rosa", "La Batalla de Waterloo", "La Batalla de Ayacucho"),
        Q(3, 2, "¿En qué año se abolió el ejército en Costa Rica?", "1948", "1821", "1856", "1990"),
        Q(3, 1, "¿Qué océano está al oeste de Costa Rica?", "El océano Pacífico", "El océano Atlántico", "El océano Índico", "El océano Ártico"),
        Q(3, 1, "¿Qué mar baña la costa este de Costa Rica?", "El mar Caribe", "El mar Mediterráneo", "El mar Rojo", "El mar Negro"),
        Q(3, 1, "¿Con qué país limita Costa Rica al norte?", "Nicaragua", "Panamá", "Honduras", "Colombia"),
        Q(3, 1, "¿Con qué país limita Costa Rica al sur?", "Panamá", "Nicaragua", "Colombia", "Venezuela"),
        Q(3, 0, "¿Cuál es la moneda de Costa Rica?", "El colón", "El dólar", "El peso", "El quetzal"),
        Q(3, 1, "¿De qué provincia es capital la ciudad de Liberia?", "Guanacaste", "Puntarenas", "Limón", "Alajuela"),
        Q(3, 1, "¿Qué volcán está en la provincia de Cartago?", "Irazú", "Arenal", "Poás", "Rincón de la Vieja"),
        Q(3, 1, "¿Cuál es la provincia más grande de Costa Rica?", "Puntarenas", "San José", "Heredia", "Cartago"),
        Q(3, 0, "¿Cuáles son los colores de la bandera de Costa Rica?", "Azul, blanco y rojo", "Verde, blanco y rojo", "Azul y blanco", "Rojo, amarillo y azul"),
        Q(3, 2, "¿Quién escribió la letra del Himno Nacional de Costa Rica?", "José María Zeledón", "Manuel María Gutiérrez", "Juan Santamaría", "Aquileo Echeverría"),
        Q(3, 2, "¿Qué se celebra en Costa Rica el 1 de diciembre?", "La abolición del ejército", "La independencia", "La anexión de Nicoya", "El día del trabajo"),
        Q(3, 2, "¿Cuál es el continente más grande?", "Asia", "África", "América", "Europa"),
        Q(3, 1, "¿Cuál es el océano más grande del mundo?", "Pacífico", "Atlántico", "Índico", "Ártico"),
        Q(3, 2, "¿Quién llegó a América en 1492?", "Cristóbal Colón", "Hernán Cortés", "Simón Bolívar", "Américo Vespucio"),
        Q(3, 2, "¿Cuál es el río más largo de América?", "El Amazonas", "El Misisipi", "El Orinoco", "El Paraná"),
        Q(3, 0, "¿En qué continente está Costa Rica?", "América", "Europa", "África", "Asia"),
        Q(3, 1, "¿Qué instrumento sirve para saber dónde está el norte?", "La brújula", "El termómetro", "El reloj", "La balanza"),
        Q(3, 2, "¿Qué línea imaginaria divide la Tierra en norte y sur?", "El ecuador", "El meridiano de Greenwich", "El trópico de Cáncer", "El polo norte"),
        Q(3, 2, "¿Cómo se llaman las líneas imaginarias que van de polo a polo?", "Meridianos", "Paralelos", "Trópicos", "Ecuadores"),
        // ---- 1.° y 2.° grado ----
        Q(0, 0, "¿Cuánto es 5 + 3?", "8", "7", "9", "6"),
        Q(0, 0, "¿Cuánto es 10 − 4?", "6", "5", "7", "4"),
        Q(0, 0, "¿Cuánto es 7 + 6?", "13", "12", "14", "11"),
        Q(0, 0, "¿Qué número va antes del 20?", "19", "21", "18", "10"),
        Q(0, 0, "¿Cuántos lados tiene un triángulo?", "3", "4", "5", "6"),
        Q(0, 0, "¿Cuántos días tiene una semana?", "7", "5", "6", "10"),
        Q(0, 0, "¿Cuántos meses tiene un año?", "12", "10", "11", "7"),
        Q(0, 0, "¿Cuál de estos números es par?", "8", "7", "5", "3"),
        Q(0, 0, "¿Cuánto es 2 × 5?", "10", "7", "12", "25"),
        Q(0, 0, "¿Cuántas unidades hay en una decena?", "10", "5", "100", "12"),
        Q(0, 0, "¿Qué figura no tiene esquinas?", "El círculo", "El cuadrado", "El triángulo", "El rectángulo"),
        Q(0, 0, "Si tengo 15 confites y me como 5, ¿cuántos me quedan?", "10", "20", "15", "5"),
        Q(0, 0, "¿Cuál de estos números es el mayor?", "45", "39", "41", "28"),
        Q(0, 0, "¿Cuánto es 20 + 30?", "50", "23", "40", "60"),
        Q(0, 0, "¿Cuántas patas tienen 2 perros juntos?", "8", "4", "6", "10"),
        Q(0, 0, "¿Cuánto es la mitad de 10?", "5", "2", "4", "20"),
        Q(1, 0, "¿Con qué letra empieza «elefante»?", "E", "L", "A", "F"),
        Q(1, 0, "¿Cuál de estas letras es una vocal?", "U", "M", "S", "T"),
        Q(1, 0, "¿Cuántas sílabas tiene la palabra «casa»?", "2", "1", "3", "4"),
        Q(1, 0, "¿Cuál palabra se escribe con mayúscula por ser un nombre?", "María", "mesa", "perro", "flor"),
        Q(1, 0, "¿Cuál es el plural de «flor»?", "Flores", "Flors", "Floras", "Flor"),
        Q(1, 0, "¿Cuál es lo contrario de «arriba»?", "Abajo", "Encima", "Lejos", "Afuera"),
        Q(1, 0, "¿Qué signo se pone al final de una oración?", "El punto", "La coma", "El guion", "La tilde"),
        Q(1, 0, "¿Qué palabra rima con «gato»?", "Pato", "Perro", "Mesa", "Sol"),
        Q(1, 0, "¿Cuál de estas palabras es un animal?", "Conejo", "Silla", "Nube", "Carro"),
        Q(1, 0, "¿Qué letra falta en «p_lota»?", "E", "A", "I", "U"),
        Q(1, 0, "¿Cuál es lo contrario de «frío»?", "Caliente", "Helado", "Mojado", "Fresco"),
        Q(1, 0, "¿Cuántas letras tiene la palabra «sol»?", "3", "2", "4", "5"),
        Q(2, 0, "¿Qué necesita una planta para vivir?", "Agua y luz", "Solo piedras", "Solo sombra", "Arena seca"),
        Q(2, 0, "¿Cuántos sentidos tenemos?", "5", "3", "4", "7"),
        Q(2, 0, "¿Con qué parte del cuerpo vemos?", "Los ojos", "Las orejas", "La nariz", "Las manos"),
        Q(2, 0, "¿Cuál de estos animales pone huevos?", "La gallina", "La vaca", "El perro", "El gato"),
        Q(2, 0, "¿Cuál de estos animales vive en el agua?", "El pez", "El caballo", "El león", "El conejo"),
        Q(2, 0, "¿Cuál de estos es un ser vivo?", "Un árbol", "Una piedra", "Una silla", "Un carro"),
        Q(2, 0, "¿De qué color son las hojas de casi todas las plantas?", "Verde", "Azul", "Morado", "Negro"),
        Q(2, 0, "¿Qué astro nos da luz y calor durante el día?", "El Sol", "La Luna", "Una nube", "Un cometa"),
        Q(2, 0, "¿Qué cubre el cuerpo de las aves?", "Plumas", "Escamas", "Pelo", "Caparazón"),
        Q(2, 0, "¿Qué cae de las nubes cuando llueve?", "Agua", "Arena", "Hojas", "Humo"),
        Q(2, 0, "¿Cómo se llama la cría de la vaca?", "Ternero", "Potro", "Cachorro", "Pollito"),
        Q(2, 0, "¿Qué debemos hacer antes de comer?", "Lavarnos las manos", "Correr", "Dormir", "Ver televisión"),
        Q(2, 0, "¿Qué animal tiene trompa?", "El elefante", "La jirafa", "El caballo", "El oso"),
        Q(3, 0, "¿Quién trabaja apagando incendios?", "El bombero", "El doctor", "El panadero", "El maestro"),
        Q(3, 0, "¿Quién nos atiende cuando estamos enfermos?", "El médico", "El cartero", "El pintor", "El chofer"),
        Q(3, 0, "¿Qué animales jalan la carreta típica de Costa Rica?", "Los bueyes", "Los caballos", "Los perros", "Los burros"),
        Q(3, 0, "¿Cuál es una comida típica de Costa Rica?", "El gallo pinto", "La pizza", "El sushi", "La hamburguesa"),
        Q(3, 0, "¿Qué día se celebra el Día de la Madre en Costa Rica?", "15 de agosto", "10 de mayo", "25 de diciembre", "1 de enero"),
        Q(3, 0, "¿Qué llevan los niños en el desfile de la noche del 14 de setiembre?", "Un farol", "Un paraguas", "Una pelota", "Un libro"),
        Q(3, 0, "¿En qué lugar estudian los niños?", "La escuela", "El hospital", "El banco", "La farmacia"),
        Q(3, 0, "¿Cuál símbolo patrio se canta?", "El himno nacional", "La bandera", "El escudo", "La guaria morada"),
        Q(3, 0, "¿Qué medio de transporte viaja por el agua?", "El barco", "El bus", "El tren", "La bicicleta"),
        Q(3, 0, "¿Qué señal de tránsito indica que hay que detenerse?", "Alto", "Ceda", "Curva", "Escuela"),
        Q(3, 0, "¿Cómo se llama la persona que dirige la escuela?", "El director", "El conserje", "El chofer", "El vecino"),
        Q(3, 0, "¿Dónde compramos las medicinas?", "En la farmacia", "En la panadería", "En la zapatería", "En la librería"),
        // ---- 5.° y 6.° grado ----
        Q(0, 2, "¿Cuánto es 12 × 12?", "144", "124", "132", "142"),
        Q(0, 2, "¿Cuál es el 50 % de 80?", "40", "50", "30", "8"),
        Q(0, 2, "¿Cuánto son 3/4 de 100?", "75", "34", "25", "70"),
        Q(0, 2, "¿Cuántos lados tiene un pentágono?", "5", "6", "7", "8"),
        Q(0, 2, "¿Cuál es el área de un triángulo de base 6 cm y altura 4 cm?", "12 cm²", "24 cm²", "10 cm²", "20 cm²"),
        Q(0, 2, "¿Cuántos milímetros tiene un centímetro?", "10", "100", "1000", "5"),
        Q(1, 2, "¿Cómo se llama la figura que compara usando la palabra «como»?", "Símil", "Metáfora", "Onomatopeya", "Hipérbole"),
        Q(1, 2, "En «Los niños juegan en el parque», ¿cuál es el sujeto?", "Los niños", "Juegan", "El parque", "En el parque"),
        Q(1, 2, "¿En qué tiempo está el verbo «comeré»?", "Futuro", "Pasado", "Presente", "Infinitivo"),
        Q(1, 2, "¿Cuál de estas palabras es un sustantivo abstracto?", "Amistad", "Mesa", "Perro", "Lápiz"),
        Q(1, 2, "¿Qué es una onomatopeya?", "Palabra que imita un sonido", "Un tipo de poema", "Palabra con tilde", "Un sinónimo"),
        Q(1, 2, "¿Cuál de estas palabras es un conector?", "Además", "Rápido", "Casa", "Cantar"),
        Q(2, 2, "¿Cuál es la unidad más pequeña de los seres vivos?", "La célula", "El órgano", "El tejido", "El hueso"),
        Q(2, 2, "¿Qué planeta es conocido como el planeta rojo?", "Marte", "Venus", "Saturno", "Neptuno"),
        Q(2, 2, "¿Qué fuerza nos mantiene pegados al suelo?", "La gravedad", "El magnetismo", "La fricción", "El viento"),
        Q(2, 2, "¿Cómo se llama la energía de un objeto en movimiento?", "Cinética", "Potencial", "Química", "Eléctrica"),
        Q(2, 2, "¿Qué órganos filtran la sangre y producen la orina?", "Los riñones", "El hígado", "El estómago", "Los pulmones"),
        Q(2, 2, "¿Qué sustancia les da el color verde a las hojas?", "La clorofila", "La savia", "El almidón", "El néctar"),
        Q(2, 2, "¿Qué planeta tiene los anillos más visibles?", "Saturno", "Marte", "Mercurio", "Venus"),
        Q(2, 2, "¿Cómo se llama la capa de gases que rodea la Tierra?", "La atmósfera", "La corteza", "El manto", "El núcleo"),
        Q(3, 2, "¿Quién fue el primer jefe de Estado de Costa Rica?", "Juan Mora Fernández", "Juan Rafael Mora Porras", "José Figueres Ferrer", "Braulio Carrillo"),
        Q(3, 2, "¿Qué presidente abolió el ejército en 1948?", "José Figueres Ferrer", "Juan Rafael Mora Porras", "Óscar Arias Sánchez", "Braulio Carrillo"),
        Q(3, 2, "¿Qué costarricense recibió el Premio Nobel de la Paz en 1987?", "Óscar Arias Sánchez", "José Figueres Ferrer", "Juan Santamaría", "Juan Mora Fernández"),
        Q(3, 2, "¿Cuál es el punto más alto de Costa Rica?", "El cerro Chirripó", "El volcán Irazú", "El volcán Arenal", "El volcán Poás"),
    )

    private val recent = ArrayDeque<Int>()

    /** Grado escolar (1..6) → grupo de preguntas: 0 = 1.° y 2.°, 1 = 3.° y 4.°, 2 = 5.° y 6.° */
    fun band(grade: Int) = ((grade.coerceIn(1, 6) - 1) / 2)

    /** level 0: todas las materias; 1..4: una materia. */
    fun pick(level: Int, grade: Int, n: Int, rnd: Random): List<Question> {
        val b = band(grade)
        val pool = BANK.indices.filter { (level == 0 || BANK[it].s == level - 1) && BANK[it].band == b }
        var fresh = pool.filter { it !in recent }
        if (fresh.size < n) { recent.clear(); fresh = pool }
        val chosen = fresh.shuffled(rnd).take(n)
        for (i in chosen) { recent.addLast(i); if (recent.size > 60) recent.removeFirst() }
        return chosen.map { i ->
            val q = BANK[i]
            val opts = q.opts.toList().shuffled(rnd)
            Question(SUBJECTS[q.s], q.text, opts, opts.indexOf(q.opts[0]))
        }
    }
}

/** Matemáticas y Trivia: 10 preguntas con 4 opciones. Con 2 jugadores, pantalla dividida y las mismas preguntas. */
class QuizScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private val N = 10
    private val two = m.players == 2
    private val math = m.game == GameId.MATE
    private var qs: List<Question> = emptyList()

    private inner class Side(val p: Int) {
        var idx = 0; var ok = 0; var picked = -1; var pickedT = 0L; var doneT = -1L
        val rect = RectF()
    }

    private var sides = listOf(Side(0))

    init { restart() }

    override fun restart() {
        qs = if (math) List(N) { Mate.gen(m.level, rnd) } else Trivia.pick(m.level, m.grade, N, rnd)
        sides = if (two) listOf(Side(0), Side(1)) else listOf(Side(0))
        resetClock(); layout()
    }

    override fun size(w: Float, h: Float) { super.size(w, h); layout() }

    private fun layout() {
        if (w == 0f) return
        val top = barH + pad
        if (two) {
            sides[0].rect.set(pad, top, w / 2 - pad / 2, h - pad)
            sides[1].rect.set(w / 2 + pad / 2, top, w - pad, h - pad)
        } else sides[0].rect.set(pad * 2, top, w - pad * 2, h - pad)
    }

    private fun headH(s: Side) = if (two) s.rect.height() * 0.13f else 0f

    private fun questionRect(s: Side, out: RectF): RectF {
        val r = s.rect
        out.set(r.left, r.top + headH(s), r.right, r.top + headH(s) + (r.height() - headH(s)) * 0.42f)
        return out
    }

    private fun optRect(s: Side, i: Int, out: RectF): RectF {
        val q = questionRect(s, RectF())
        val gap = pad * 0.8f
        val top = q.bottom + gap
        val bw = (s.rect.width() - gap) / 2
        val bh = (s.rect.bottom - top - gap) / 2
        val col = i % 2; val row = i / 2
        out.set(s.rect.left + col * (bw + gap), top + row * (bh + gap), s.rect.left + col * (bw + gap) + bw, top + row * (bh + gap) + bh)
        return out
    }

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        val txt = if (two) (if (math) "10 operaciones" else "10 preguntas de ${m.grade}.° grado") else {
            val s = sides[0]
            "Pregunta ${min(s.idx + 1, N)} de $N     Correctas ${s.ok}     ${G.timeExact(if (s.doneT >= 0) s.doneT else gt())}"
        }
        G.textFit(c, txt, (left + right) / 2, barH / 2, barH * 0.34f, right - left, if (two) C.TEXT2 else C.WHITE, font = Fonts.black)
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        val qr = RectF(); val or = RectF()
        for (s in sides) {
            if (s.picked >= 0 && !blocked) {
                val q = qs[s.idx]
                if (t - s.pickedT >= (if (s.picked == q.correct) 650 else 1400)) {
                    s.picked = -1; s.idx++
                    if (s.idx >= N) { s.doneT = t; checkEnd() }
                }
            }
            if (two) {
                val col = C.PLAYER[s.p]
                G.rr(c, s.rect, barH * 0.25f, C.alpha(col, 0.07f))
                G.textFit(c, "${name(s.p)}   ${s.ok}/$N", s.rect.centerX(), s.rect.top + headH(s) * 0.5f, headH(s) * 0.6f, s.rect.width() * 0.9f, col, font = Fonts.black)
            }
            if (s.idx >= N) {
                val other = sides.firstOrNull { it !== s && it.idx < N }
                G.lines(c, "¡Terminaste!\n${s.ok} de $N correctas" + (if (other != null) "\nEsperando a ${name(other.p)}..." else ""),
                    s.rect.centerX(), s.rect.centerY(), barH * 0.45f, s.rect.width() * 0.85f, C.WHITE, font = Fonts.black)
                continue
            }
            val q = qs[s.idx]
            questionRect(s, qr)
            G.rr(c, qr, barH * 0.25f, C.PANEL)
            if (q.subject.isNotEmpty()) G.textFit(c, "${q.subject}  ·  ${s.idx + 1}/$N", qr.centerX(), qr.top + qr.height() * 0.15f, barH * 0.27f, qr.width() * 0.9f, C.AMBER)
            else if (two) G.text(c, "${s.idx + 1}/$N", qr.centerX(), qr.top + qr.height() * 0.15f, barH * 0.27f, C.AMBER)
            if (math) {
                G.textFit(c, q.text, qr.centerX(), qr.centerY() + qr.height() * 0.06f, qr.height() * 0.5f, qr.width() * 0.9f, C.WHITE, font = Fonts.black)
            } else {
                paragraph(c, q.text, qr.centerX(), qr.centerY() + qr.height() * 0.08f, qr.width() * 0.92f, qr.height() * 0.7f, barH * (if (two) 0.42f else 0.5f))
            }
            for (i in 0 until 4) {
                optRect(s, i, or)
                val bg = when {
                    s.picked < 0 -> if (ui.pressed(or)) C.CARD_HI else C.CARD
                    i == q.correct -> C.GREEN
                    i == s.picked -> C.RED
                    else -> C.alpha(C.CARD, 0.45f)
                }
                G.rr(c, or, or.height() * 0.2f, bg)
                val fg = if (s.picked >= 0 && (i == q.correct || i == s.picked)) C.DARK else C.WHITE
                val sz = if (math) or.height() * 0.5f else or.height() * 0.32f
                val label = q.options[i]
                if (label.contains(' ') && G.fit(label, or.width() * 0.88f, sz, Fonts.black) < sz * 0.75f)
                    G.lines(c, wrap2(label), or.centerX(), or.centerY(), sz, or.width() * 0.88f, fg, font = Fonts.black, gap = 1.15f)
                else G.textFit(c, label, or.centerX(), or.centerY(), sz, or.width() * 0.88f, fg, font = Fonts.black)
            }
        }
    }

    /** Texto con ajuste de línea dentro de una caja. */
    private fun paragraph(c: Canvas, text: String, cx: Float, cy: Float, maxW: Float, maxH: Float, size0: Float) {
        var size = size0
        var lines: List<String>
        while (true) {
            lines = wrap(text, maxW, size)
            if (lines.size * size * 1.25f <= maxH || size < size0 * 0.5f) break
            size *= 0.92f
        }
        G.lines(c, lines.joinToString("\n"), cx, cy, size, maxW, C.WHITE, font = Fonts.black, gap = 1.22f)
    }

    private fun wrap(text: String, maxW: Float, size: Float): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (word in text.split(" ")) {
            val tryLine = if (cur.isEmpty()) word else "$cur $word"
            if (G.width(tryLine, size, Fonts.black) <= maxW || cur.isEmpty()) cur = tryLine else { out.add(cur); cur = word }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    override fun touchDown(id: Int, x: Float, y: Float) {
        if (blocked) return
        val s = sides.firstOrNull { it.rect.contains(x, y) } ?: return
        if (s.idx >= N || s.picked >= 0) return
        val r = RectF()
        for (i in 0 until 4) if (optRect(s, i, r).contains(x, y)) {
            s.picked = i; s.pickedT = gt()
            if (i == qs[s.idx].correct) { s.ok++; Sound.play(Sound.GOOD) } else Sound.play(Sound.BAD)
            return
        }
    }

    private fun checkEnd() {
        if (sides.any { it.idx < N }) return
        val key = if (math) "mate" else "trivia"
        if (!two) {
            val s = sides[0]
            val lv = if (math) "${m.level}" else "g${Trivia.band(m.grade)}"
            val nr1 = Store.best("${key}_ok$lv", s.ok.toLong(), false)
            val nr2 = s.ok == N && Store.best("${key}_t$lv", s.doneT, true)
            Store.inc("${key}_partidas")
            val title = when { s.ok == N -> "¡Perfecto!"; s.ok >= 7 -> "¡Muy bien!"; s.ok >= 5 -> "¡Bien hecho!"; else -> "¡Sigue practicando!" }
            finish(Result(Result.SOLO, title, "${s.ok} de $N correctas\nTiempo: ${G.timeExact(s.doneT)}" + if (nr1 || nr2) "\n¡Nuevo récord!" else ""), 400)
        } else {
            val a = sides[0]; val b = sides[1]
            val d = "${name(0)}: ${a.ok} correctas en ${G.timeExact(a.doneT)}\n${name(1)}: ${b.ok} correctas en ${G.timeExact(b.doneT)}"
            val win = when {
                a.ok != b.ok -> if (a.ok > b.ok) 0 else 1
                a.doneT != b.doneT -> if (a.doneT < b.doneT) 0 else 1
                else -> -1
            }
            finish(if (win < 0) Result(Result.DRAW, "Empate", d) else Result(win, "¡Ganó ${name(win)}!", d), 400)
        }
    }
}

/** Ahorcado: adivinar la palabra eligiendo letras. */
class AhorcadoScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private val MAX = 6
    private val ROUNDS = 3 // palabras por jugador con 2 jugadores
    private val two = m.players == 2
    private val KEYS = "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ"

    private var cat = ""; private var word = ""
    private val used = HashSet<Char>()
    private var errors = 0
    private var state = 0 // 0 jugando, 1 ganó la palabra, 2 perdió
    private var stateT = 0L
    private var turn = 0
    private var round = 0
    private var intro = false
    private val solved = IntArray(2)
    private val errs = IntArray(2)

    init { restart() }

    override fun restart() {
        turn = m.starter; round = 0; solved.fill(0); errs.fill(0)
        resetClock(); newWord(); intro = two
    }

    private fun base(ch: Char) = when (ch) { 'Á' -> 'A'; 'É' -> 'E'; 'Í' -> 'I'; 'Ó' -> 'O'; 'Ú', 'Ü' -> 'U'; else -> ch }

    private fun newWord() {
        val (c2, w2) = Words.pick(m.level, rnd)
        cat = c2; word = w2; used.clear(); errors = 0; state = 0
        if (m.level == 0) used.add(base(word.first { it != ' ' }))
    }

    private fun revealed(ch: Char) = ch == ' ' || base(ch) in used
    private fun complete() = word.all { revealed(it) }

    private fun guess(k: Char) {
        if (state != 0 || k in used || intro || blocked) return
        used.add(k)
        if (word.any { base(it) == k }) {
            Sound.play(Sound.POP)
            if (complete()) { state = 1; stateT = gt(); Sound.play(Sound.GOOD); endWord(true) }
        } else {
            errors++; Sound.play(Sound.BAD)
            if (errors >= MAX) { state = 2; stateT = gt(); endWord(false) }
        }
    }

    private fun endWord(ok: Boolean) {
        if (!two) {
            if (ok) {
                Store.inc("ahorcado_palabras")
                val streak = Store.long("ahorcado_racha_actual") + 1
                Store.put("ahorcado_racha_actual", streak)
                val nr = Store.best("ahorcado_racha", streak, false)
                finish(Result(Result.SOLO, "¡Adivinaste!", "La palabra era $word\nRacha: $streak" + if (nr && streak > 1) "  ¡Nuevo récord!" else ""), 1200)
            } else {
                Store.put("ahorcado_racha_actual", 0)
                finish(Result(Result.SOLO, "¡Ahorcado!", "La palabra era $word"), 1500)
            }
            return
        }
        if (ok) solved[turn]++
        errs[turn] += errors
    }

    private fun nextTurn() {
        if (turn != m.starter) round++
        turn = 1 - turn
        if (round >= ROUNDS) { decide(); return }
        newWord(); intro = true
    }

    private fun decide() {
        val d = "${name(0)}: ${solved[0]} palabras, ${errs[0]} errores\n${name(1)}: ${solved[1]} palabras, ${errs[1]} errores"
        val win = when {
            solved[0] != solved[1] -> if (solved[0] > solved[1]) 0 else 1
            errs[0] != errs[1] -> if (errs[0] < errs[1]) 0 else 1
            else -> -1
        }
        finish(if (win < 0) Result(Result.DRAW, "Empate", d) else Result(win, "¡Ganó ${name(win)}!", d), 300)
    }

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        val txt = if (two) "${name(0)} ${solved[0]}  ·  ${name(1)} ${solved[1]}     Palabra ${min(round + 1, ROUNDS)} de $ROUNDS"
        else "Errores $errors de $MAX     Racha ${Store.long("ahorcado_racha_actual")}"
        G.textFit(c, txt, (left + right) / 2, barH / 2, barH * 0.34f, right - left, C.WHITE, font = Fonts.black)
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        val top = barH + pad
        val keyRows = 2
        val keyGap = pad * 0.5f
        val perRow = 14
        val keyW = (w - pad * 4 - keyGap * (perRow - 1)) / perRow
        val keyH = min(keyW * 0.95f, (h - top) * 0.17f)
        val kbTop = h - pad * 1.5f - keyRows * keyH - keyGap
        // Horca
        val gw = min(w * 0.3f, (kbTop - top) * 0.95f)
        val gx = pad * 3; val gy = top + pad * 0.5f
        drawGallows(c, gx, gy, gw, kbTop - top - pad * 1.5f, t)
        // Palabra
        val wx = gx + gw + pad * 3; val ww = w - wx - pad * 3
        val playerCol = if (two) C.PLAYER[turn] else C.AMBER
        val catTxt = "Tema: $cat"
        G.textFit(c, catTxt, wx + ww / 2, top + (kbTop - top) * 0.2f, barH * 0.42f, ww, playerCol, font = Fonts.black)
        if (two) G.textFit(c, "Juega ${name(turn)}", wx + ww / 2, top + (kbTop - top) * 0.08f, barH * 0.3f, ww, C.TEXT2)
        val n = word.length
        val slot = min(ww / n, keyH * 1.25f)
        val sy = top + (kbTop - top) * 0.55f
        val sx0 = wx + (ww - slot * n) / 2
        for ((i, ch) in word.withIndex()) {
            val cx = sx0 + slot * (i + 0.5f)
            if (ch == ' ') continue
            c.drawLine(cx - slot * 0.38f, sy + slot * 0.55f, cx + slot * 0.38f, sy + slot * 0.55f, G.stroke(C.LINE, slot * 0.07f))
            val show = revealed(ch) || state == 2
            if (show) G.text(c, ch.toString(), cx, sy, slot * 0.8f, if (revealed(ch)) C.WHITE else C.RED, font = Fonts.black)
        }
        if (state == 1) G.text(c, "¡Muy bien!", wx + ww / 2, top + (kbTop - top) * 0.88f, barH * 0.5f, C.GREEN, font = Fonts.black)
        if (state == 2) G.text(c, "¡Ahorcado!", wx + ww / 2, top + (kbTop - top) * 0.88f, barH * 0.5f, C.RED, font = Fonts.black)
        // Teclado
        for ((i, k) in KEYS.withIndex()) {
            val row = i / perRow; val col = i % perRow
            val rowCount = if (row == 0) perRow else KEYS.length - perRow
            val rowX = (w - (rowCount * keyW + (rowCount - 1) * keyGap)) / 2
            val r = RectF(rowX + col * (keyW + keyGap), kbTop + row * (keyH + keyGap), rowX + col * (keyW + keyGap) + keyW, kbTop + row * (keyH + keyGap) + keyH)
            val usedK = k in used
            val hit = usedK && word.any { base(it) == k }
            if (usedK) {
                G.rr(c, r, keyH * 0.2f, if (hit) C.alpha(C.GREEN, 0.35f) else C.alpha(C.RED, 0.18f))
                G.text(c, k.toString(), r.centerX(), r.centerY(), keyH * 0.5f, if (hit) C.WHITE else C.alpha(C.WHITE, 0.3f), font = Fonts.black)
            } else {
                ui.button(c, r, k.toString(), Btn.CARD, keyH * 0.5f, enabled = state == 0 && !intro) { guess(k) }
            }
        }
        // Siguiente turno en 2 jugadores
        if (two && state != 0 && !over && t - stateT > 900) {
            val bw = ww * 0.6f; val bh = barH * 0.9f
            val last = round >= ROUNDS - 1 && turn != m.starter
            ui.button(c, RectF(wx + ww / 2 - bw / 2, top + (kbTop - top) * 0.75f, wx + ww / 2 + bw / 2, top + (kbTop - top) * 0.75f + bh),
                if (last) "Ver resultado" else "Siguiente", Btn.PRIMARY) { nextTurn() }
        }
        if (intro) drawIntro(c)
    }

    private fun drawIntro(c: Canvas) {
        G.rr(c, 0f, barH, w, h, 0f, C.alpha(C.BG, 0.88f))
        G.text(c, "Palabra ${round + 1} de $ROUNDS para", w / 2, h * 0.32f, barH * 0.42f, C.TEXT2)
        G.textFit(c, name(turn), w / 2, h * 0.47f, barH * 0.9f, w * 0.7f, C.PLAYER[turn], font = Fonts.black)
        G.text(c, "El otro jugador no le puede ayudar", w / 2, h * 0.6f, barH * 0.32f, C.WHITE)
        val bw = w * 0.3f; val bh = barH
        ui.button(c, RectF(w / 2 - bw / 2, h * 0.7f, w / 2 + bw / 2, h * 0.7f + bh), "Empezar", Btn.PRIMARY) { intro = false }
    }

    private fun drawGallows(c: Canvas, x: Float, y: Float, gw: Float, gh: Float, t: Long) {
        G.rr(c, x, y, x + gw, y + gh, gw * 0.06f, C.PANEL)
        val s = min(gw, gh)
        val cx = x + gw / 2
        val base = y + gh * 0.9f
        val wood = G.stroke(0xFF8A6A4A.toInt(), s * 0.04f)
        c.drawLine(cx - s * 0.38f, base, cx + s * 0.3f, base, wood)
        val pole = cx - s * 0.22f
        c.drawLine(pole, base, pole, y + gh * 0.1f, wood)
        c.drawLine(pole, y + gh * 0.1f, cx + s * 0.16f, y + gh * 0.1f, wood)
        c.drawLine(pole, y + gh * 0.24f, pole + s * 0.14f, y + gh * 0.1f, wood)
        val hx = cx + s * 0.16f
        c.drawLine(hx, y + gh * 0.1f, hx, y + gh * 0.2f, G.stroke(0xFFC9B48A.toInt(), s * 0.02f))
        val col = if (state == 2) C.RED else C.WHITE
        val p = G.stroke(col, s * 0.035f)
        val headR = s * 0.075f
        val headY = y + gh * 0.2f + headR
        val neck = headY + headR
        val hip = neck + gh * 0.24f
        if (errors >= 1) {
            c.drawCircle(hx, headY, headR, p)
            if (state == 2) {
                val e = headR * 0.3f; val pe = G.stroke(col, s * 0.015f)
                for (sx in floatArrayOf(-0.35f, 0.35f)) {
                    val ex = hx + headR * sx; val ey = headY - headR * 0.15f
                    c.drawLine(ex - e, ey - e, ex + e, ey + e, pe); c.drawLine(ex - e, ey + e, ex + e, ey - e, pe)
                }
            } else if (state == 1) {
                G.r.set(hx - headR * 0.5f, headY - headR * 0.3f, hx + headR * 0.5f, headY + headR * 0.5f)
                c.drawArc(G.r, 20f, 140f, false, G.stroke(col, s * 0.015f))
            }
        }
        if (errors >= 2) c.drawLine(hx, neck, hx, hip, p)
        if (errors >= 3) c.drawLine(hx, neck + gh * 0.05f, hx - s * 0.12f, neck + gh * 0.15f, p)
        if (errors >= 4) c.drawLine(hx, neck + gh * 0.05f, hx + s * 0.12f, neck + gh * 0.15f, p)
        if (errors >= 5) c.drawLine(hx, hip, hx - s * 0.1f, hip + gh * 0.16f, p)
        if (errors >= 6) c.drawLine(hx, hip, hx + s * 0.1f, hip + gh * 0.16f, p)
        // Vidas restantes
        for (i in 0 until MAX) {
            val dx = x + gw * 0.12f + i * gw * 0.08f
            G.circle(c, dx, y + gh * 0.96f - s * 0.02f, s * 0.022f, if (i < MAX - errors) C.AMBER else C.alpha(C.WHITE, 0.15f))
        }
    }
}

object Words {
    private val CATS = linkedMapOf(
        "Animales" to "PERRO GATO ELEFANTE JIRAFA TORTUGA MARIPOSA CABALLO CONEJO TUCÁN PEREZOSO COCODRILO BALLENA DELFÍN CANGURO MURCIÉLAGO ARDILLA PINGÜINO TIBURÓN LORO ABEJA",
        "Frutas" to "MANZANA BANANO PIÑA SANDÍA FRESA MANGO NARANJA PAPAYA LIMÓN UVA MELÓN GUAYABA CEREZA MARACUYÁ COCO PERA KIWI",
        "Países" to "PANAMÁ MÉXICO BRASIL ARGENTINA COLOMBIA ESPAÑA CANADÁ JAPÓN CHINA FRANCIA ITALIA ALEMANIA PERÚ CHILE NICARAGUA EGIPTO AUSTRALIA",
        "La escuela" to "LÁPIZ CUADERNO BORRADOR MOCHILA PIZARRA REGLA TIJERAS LIBRO MAESTRA RECREO CALCULADORA SACAPUNTAS ESCRITORIO COMPÁS",
        "Deportes" to "FÚTBOL BALONCESTO NATACIÓN TENIS BÉISBOL CICLISMO VOLEIBOL ATLETISMO SURF KARATE AJEDREZ BOXEO",
        "El cuerpo" to "CORAZÓN CEREBRO PULMONES ESTÓMAGO RODILLA HOMBRO CODO OREJA NARIZ ESQUELETO MÚSCULO TOBILLO",
        "Oficios" to "DOCTOR BOMBERO POLICÍA PANADERO CARPINTERO PILOTO ENFERMERA ASTRONAUTA VETERINARIO AGRICULTOR",
        "Naturaleza" to "VOLCÁN MONTAÑA CASCADA PLAYA BOSQUE RÍO ARCOÍRIS ESTRELLA NUBE RELÁMPAGO ISLA SELVA",
    )
    private val ALL: List<Pair<String, String>> = CATS.flatMap { (k, v) -> v.split(" ").map { k to it } }
    private val recent = ArrayDeque<String>()

    /** level 0: palabras cortas, 1: todas, 2: largas. */
    fun pick(level: Int, rnd: Random): Pair<String, String> {
        val pool = ALL.filter { (_, w) -> when (level) { 0 -> w.length <= 6; 2 -> w.length >= 8; else -> true } }
        val fresh = pool.filter { it.second !in recent }.ifEmpty { recent.clear(); pool }
        val p = fresh[rnd.nextInt(fresh.size)]
        recent.addLast(p.second); if (recent.size > 30) recent.removeFirst()
        return p
    }
}
