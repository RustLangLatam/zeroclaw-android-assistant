package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {

    @Test
    fun theMenuOpensOnlyWhenAskedFor() {
        assertEquals(Command.MENU, Commands.of("Ksandra, muéstrame el menú."))
        assertEquals(Command.MENU, Commands.of("menú"))
        assertEquals(Command.MENU, Commands.of("Abre las opciones, por favor"))
        assertEquals(Command.CLOSE, Commands.of("Cerrar menú"))
    }

    @Test
    fun placesAndTheSwitchByName() {
        assertEquals(Command.ACTIVITY, Commands.of("Muéstrame mi actividad"))
        assertEquals(Command.CONTROL, Commands.of("abre ajustes"))
        assertEquals(Command.CONTROL, Commands.of("Ksandra, muéstrame el menú de configuraciones"))
        assertEquals(Command.RULES, Commands.of("¿Qué puedes hacer?"))
        assertEquals(Command.PAUSE, Commands.of("Ksandra, detente"))
        assertEquals(Command.RESUME, Commands.of("reanuda"))
        assertEquals(Command.HOME, Commands.of("Llévame al inicio"))
        assertEquals(Command.WIDGETS, Commands.of("Ksandra, organiza mis widgets"))
        assertEquals(Command.HOME, Commands.of("vuelve a la pantalla principal"))
    }

    @Test
    fun theNameInWhatTheOwnerSaid() {
        assertEquals("Milton", Commands.nameFrom("Me llamo Milton."))
        assertEquals("Milton", Commands.nameFrom("llámame milton"))
        assertEquals("Ana María", Commands.nameFrom("ana maría"))
    }

    @Test
    fun questionsThatMentionAWordAreForTheAssistant() {
        assertNull(Commands.of("¿Tengo mensajes de Ana?"))
        assertNull(Commands.of("¿Cuál es el menú del restaurante de esta noche?"))
        assertNull(Commands.of("Pon una alarma a las 7"))
        assertNull(Commands.of("Para mañana, ¿qué tengo?"))
    }

    @Test
    fun theTwoModesByAsking() {
        assertEquals(Command.APP_MODE, Commands.of("Ksandra, cambia a modo app"))
        assertEquals(Command.APP_MODE, Commands.of("modo aplicación"))
        assertEquals(Command.APP_MODE, Commands.of("Quiero el modo con botones, por favor"))
        assertEquals(Command.ASSISTANT_MODE, Commands.of("Vuelve al modo asistente"))
        assertEquals(Command.ASSISTANT_MODE, Commands.of("ponte en modalidad asistente"))
        assertNull(Commands.of("¿Cuál es el mejor modo de ahorrar en una app?"))
    }

    @Test
    fun theOpenMicrophoneOnlyTakesWhatIsForHer() {
        val long = Commands.FOLLOW_UP_MS + 1
        assertTrue(Commands.addressed("Ksandra, ¿qué tengo hoy?", long))
        assertTrue(Commands.addressed("abre ajustes", long))
        assertTrue(Commands.addressed("¿y mañana?", 5_000))
        assertFalse(Commands.addressed("el partido de anoche estuvo buenísimo", long))
    }

    @Test
    fun theOwnerInterruptsHerNotHerOwnVoice() {
        val saying = "La inflación sube cuando hay más dinero circulando que bienes para comprar."
        assertTrue(Commands.interrupts("Espera", saying))
        assertTrue(Commands.interrupts("Ksandra, mejor dime el clima", saying))
        assertTrue(Commands.interrupts("¿y qué pasa con los salarios?", saying))
        // The assistant's own speech picked up by the microphone (echo) is not an interruption.
        assertFalse(Commands.interrupts("la inflación sube cuando hay más dinero", saying))
        assertFalse(Commands.interrupts("dinero", saying))
        assertFalse(Commands.interrupts("", saying))
    }

    @Test
    fun simpleAndNormalModeBySpeech() {
        assertEquals(Command.SIMPLE_MODE, Commands.of("modo sencillo"))
        assertEquals(Command.SIMPLE_MODE, Commands.of("Ponte en modo fácil"))
        assertEquals(Command.SIMPLE_MODE, Commands.of("simple mode"))
        assertEquals(Command.NORMAL_MODE, Commands.of("vuelve al modo normal"))
        assertEquals(Command.NORMAL_MODE, Commands.of("normal mode"))
        assertEquals(Command.APP_MODE, Commands.of("modo app"))
    }

    @Test fun theModelsTheIntegrationsAndTheColorThemesOpenByVoice() {
        assertEquals(Command.MODELS, Commands.of("Ksandra, muéstrame la configuración de los modelos"))
        assertEquals(Command.MODELS, Commands.of("abre los modelos de IA"))
        assertEquals(Command.MODELS, Commands.of("show me the AI models"))
        assertEquals(Command.INTEGRATIONS, Commands.of("muéstrame las integraciones"))
        assertEquals(Command.THEMES, Commands.of("¡Muéstrame los themes de colores!"))
        assertEquals(Command.THEMES, Commands.of("muéstrame los temas de colores"))
        assertEquals(Command.THEMES, Commands.of("show me the color themes"))
        assertEquals(Command.CONTROL, Commands.of("muéstrame la configuración"))
        // A question that merely mentions them still goes to the agent.
        assertEquals(null, Commands.of("qué modelos de IA conoces"))
    }
}
