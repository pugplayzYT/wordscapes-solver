package com.pugplayz.wordscapesauto

import android.content.Context
import java.util.Locale
import kotlin.math.ln

/** Pure word-generation logic. No server, no ADB, no OCR. */
object WordEngine {
    // A small rank boost for everyday words; the full bundled dictionary supplies coverage.
    private val common = ("the and are art rat tar car cat act ear eat tea ate see sea set ten net not note tone " +
        "one two three four five six seven eight nine ten tree free fire fine line mile mine time tire tier " +
        "ride read dear dare deal lead land lane lean real rail rain train trail trail rate tear tare stare " +
        "stone notes tones onset seat east eats teas tease steal slate stale least tales late tale " +
        "care race acre clear trace crate cater react acter trace caret cart carte cater crate " +
        "rest rent sent nest test best bent tent tart start star arts rats tars trap part park dark " +
        "park bark mark hand hard yard card word world work road rose sore more core score store " +
        "door food good mood wood pool cool tool soon moon room boom book cook look took " +
        "play pray pay day way say may lay bay gay ray clay stay tray try dry fly sky " +
        "ant tan ran man can fan pan van plan plane panel apple pear bear bare beer " +
        "hope rope pope soap coat boat goat moat oats goat goal load gold cold hold bold " +
        "cold told sold mold bold old owl low row bow cow how now wow sow tow toe " +
        "box fox oxo axe tax sax max wax mix fix six hit sit bit fit lit pit kit " +
        "dog god log fog hog bog jog fig dig big pig wig rig git tag rag bag sag nag " +
        "ice nice rice dice mice slice spice price place space pace ace case base vase " +
        "ever never fever lever river driver give live love dove move above after before " +
        "house mouse spouse home some come game name same tame fame lame male meal lame " +
        "man men woman women child sun son run fun bun gun nun pun puny sunny funny " +
        "red bed fed led wed bred spread brown crown down town gown own known snow " +
        "north south east west no yes yet key hey why who what where when there their " +
        "this that then than them theme these those other another about bound sound round " +
        "found pound ground about doubt out our sour four hour tour your you true rue " +
        "cup pup up upon open pen pin pine pane pain pair fair hair air stair chair " +
        "heat hate heart earth hear hare near earn year learn early clear clean can learn " +
        "stand sand band brand grand hand candy handy any many penny money honey " +
        "bird third dirt shirt skirt first fist list last lost most cost host post stop pot top " +
        "spot pots tops opts sport port sort sore sort short shore share harsh dash wash trash " +
        "much such touch couch pouch found hound sound pound luck duck suck buck truck " +
        "rock sock lock clock block black back sack tack lack track rack crack stack " +
        "blue glue clue true due sue hue cue cute mute lute tube cube rub sub tub " +
        "bring ring king sing wing ding ping thing think ink pink sink link blink stink " +
        "yellow mellow bell sell tell well shell smell spell small tall fall wall call ball " +
        "hello help held felt melt belt pelt lent vent went sent cent bent tent " +
        "take make bake cake lake rake fake sake shake stake skate takes makes " +
        "line lines lines lion iron ion onion join coin cone once nice life file fill " +
        "still hill will pill bill kill mill tell toll roll role rule ruler rural " +
        "good goon noon moon boon soon spoon spin pin pins sin sins sign sing sings " +
        "beach reach teach each ache peach cheap shape phase chase case space " +
        "age page cage rage sage wage gate date late mate rate fate hate plate")
        .split(' ').toSet()

    private var dictionary: Set<String>? = null
    private var frequency: Map<String, Int> = emptyMap()

    fun load(context: Context) {
        if (dictionary != null) return
        dictionary = context.assets.open("words.txt").bufferedReader().useLines { seq ->
            seq.map { it.trim().uppercase(Locale.US) }.filter { it.length in 3..12 }.toHashSet()
        }
        frequency = context.assets.open("word_frequency.tsv").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t')
                val count = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                parts[0].uppercase(Locale.US) to count
            }.toMap()
        }
    }

    fun isValid(word: String, letters: String): Boolean {
        if (word.length !in 3..letters.length) return false
        val remaining = IntArray(26)
        for (c in letters.uppercase(Locale.US)) {
            if (c !in 'A'..'Z') return false
            remaining[c - 'A']++
        }
        for (c in word.uppercase(Locale.US)) {
            if (c !in 'A'..'Z' || --remaining[c - 'A'] < 0) return false
        }
        return true
    }

    fun solve(letters: String, limit: Int = 130): List<String> {
        val cleaned = letters.uppercase(Locale.US)
        require(cleaned.length in 3..12 && cleaned.all { it in 'A'..'Z' })
        return (dictionary ?: emptySet()).asSequence()
            .filter { it.length <= cleaned.length && isValid(it, cleaned) }
            // Keep full-length anagrams in the candidate queue even when
            // short high-frequency words would otherwise fill the limit.
            .sortedWith(compareByDescending<String> { it.length == cleaned.length }
                .thenByDescending {
                    ln((frequency[it] ?: 0).toDouble() + 1.0) + it.length * .40 +
                        if (it.lowercase(Locale.US) in common) 1.2 else 0.0
                }.thenByDescending { it.length }.thenBy { it })
            .take(limit).toList()
    }

    /**
     * Wordscapes speed mode. The bundled 84k-entry dictionary includes names,
     * abbreviations and extremely rare spellings, so a raw word list is NOT
     * a reasonable queue to swipe. Use the known-word frequency table and
     * practical word lengths to form three progressively broader batches.
     *
     * The final batch is deliberately bounded: if ordinary answers haven't
     * finished the level, inspect the letters instead of blindly swiping
     * hundreds of nonsensical bonus words.
     */
    data class LevelBatches(
        val likely:List<String>,
        val plausible:List<String>,
        val lastResort:List<String>
    )

    // Common words missing from our web-derived frequency table.
    // This is a conservative lexical supplement, NOT a large list of
    // obscure anagrams to try before normal words.
    private val missingCommon = setOf(
        "CRATES","RECAST","ASTER","SATE","LAIR","RIAL","PEAL",
        "ELAN","PEAL","SALE","TEAL","LEST","LENT","LIRA",
        "LAIR","BRAN","BAIN","RANI","GLEAN","ANGEL","GLEAM",
        "STENO","SCARE","STARE","AISLE","LIEU","AIL",
        "EASE","LEAS","TARE","RHEA","CATER","CARETS",
        "CARTE","CLEAR","CLARET","CARTEL","TRACE","TRACES",
        "RATES","TEARS","EARTH","HEART","HATER","HATERS",
        "RETAKE","TAKER","STAKE","SKEAT"
    )

    // Very frequent in online searches but generally names/acronyms rather
    // than meaningful Wordscapes answers. They are not used in FAST mode.
    private val nonPuzzleTerms = setOf(
        "SEO","EST","ETC","WWW","PDF","URL","HTTP","HTTPS",
        "EPA","NBA","RCA","NATO","NASA","NEPAL","RITA",
        "ONS","EOS","NES","TCAS","RTE","TSE","TSO",
        "HTML","CSS","XML","USA","USPS","ISBN","SQL"
    )

    fun solveForLevel(letters:String):LevelBatches {
        val cleaned=letters.uppercase(Locale.US)
        require(cleaned.length in 3..12 && cleaned.all{it in 'A'..'Z'})
        val result=mutableListOf<String>()
        for(word in dictionary ?: emptySet()) {
            if(word.length<=cleaned.length && word !in nonPuzzleTerms &&
               isValid(word,cleaned))result.add(word)
        }
        fun isCore(word:String):Boolean {
            val rank=frequency[word] ?:0
            val isFamiliar=word.lowercase(Locale.US) in common ||
                word in missingCommon
            return isFamiliar || rank>=when(word.length) {
                3 -> 14000
                4 -> 9200
                else -> 6000
            }
        }
        fun isPlausible(word:String):Boolean {
            val rank=frequency[word] ?:0
            return word in missingCommon ||
                word.lowercase(Locale.US) in common ||
                rank>=when(word.length) {
                    3 -> 6500
                    4 -> 3200
                    else -> 1100
                }
        }
        fun isLastResort(word:String):Boolean {
            val rank=frequency[word] ?:0
            // No unidentified 3-letter abbreviations and no zero-frequency
            // words except those in our curated everyday-word supplement.
            return word in missingCommon ||
                word.lowercase(Locale.US) in common ||
                rank>=when(word.length) {
                    3 -> 4000
                    4 -> 750
                    else -> 250
                }
        }
        val ordered=result.sortedWith(compareByDescending<String> {word->
            val rank=frequency[word] ?:0
            val commonBonus=if(word.lowercase(Locale.US) in common ||
                word in missingCommon)7.0 else 0.0
            val fullBonus=if(word.length==cleaned.length)7.0 else 0.0
            rank/1000.0 + word.length*6.0 + commonBonus + fullBonus
        }.thenBy {it})
        val core=ordered.filter(::isCore)
        val intermediate=ordered.filter{!isCore(it) && isPlausible(it)}
        val last=ordered.filter{!isPlausible(it) && isLastResort(it)}
        // Limit each level to around 140 ranked plausible attempts total,
        // rather than blindly extending to 850 web-dictionary oddities.
        val phase1=core.take(45)
        val phase2=(core.drop(45)+intermediate).take(65)
        val phase3=(core.drop(45).drop(65)+
            intermediate.filter{it !in phase2} + last).distinct()
            .filter{it !in phase1 && it !in phase2}.take(35)
        return LevelBatches(phase1,phase2,phase3)
    }

    // Preserve the screen order, including duplicate letters. Every index is used at most once.
    fun path(word: String, letters: String, points: List<Spot>): List<Spot>? {
        if (letters.length != points.size) return null
        val occupied = BooleanArray(letters.length)
        val result = mutableListOf<Spot>()
        for (c in word.uppercase(Locale.US)) {
            val index = letters.indices.firstOrNull { !occupied[it] && letters[it].uppercaseChar() == c } ?: return null
            occupied[index] = true
            result.add(points[index])
        }
        return result
    }
}

data class Spot(val x: Float, val y: Float)

data class WheelResult(
    val points: List<Spot>, val confidence: Float, val inferredCount: Int,
    val center: Spot? = null, val radius: Float = 0f
)
