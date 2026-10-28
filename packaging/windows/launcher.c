/*
 * BrainOut.exe: starts the game with the Java runtime shipped next to it.
 * The game reads and writes files relative to the working directory, so it runs from game\.
 * Arguments given to BrainOut.exe are passed to the game.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wchar.h>

#define JVM_OPTIONS \
    L"-Xmx2g --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED " \
    L"--add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED " \
    L"--add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED"

/* what follows the program name on the command line */
static const wchar_t *Arguments(const wchar_t *line)
{
    int quoted = 0;

    for (; *line; line++)
    {
        if (*line == L'"')
            quoted = !quoted;
        else if (!quoted && (*line == L' ' || *line == L'\t'))
            break;
    }

    while (*line == L' ' || *line == L'\t')
        line++;

    return line;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR args, int show)
{
    static wchar_t dir[MAX_PATH], game[MAX_PATH], java[MAX_PATH], command[8192];

    DWORD length = GetModuleFileNameW(NULL, dir, MAX_PATH);
    if (length == 0 || length >= MAX_PATH)
        return 1;

    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash)
        *slash = 0;

    _snwprintf(game, MAX_PATH, L"%ls\\game", dir);
    _snwprintf(java, MAX_PATH, L"%ls\\runtime\\bin\\javaw.exe", dir);
    _snwprintf(command, 8192, L"\"%ls\" " JVM_OPTIONS L" -jar brainout-desktop.jar --offline --unsafe %ls",
        java, Arguments(GetCommandLineW()));

    STARTUPINFOW startup = {sizeof(startup)};
    PROCESS_INFORMATION process;

    if (!CreateProcessW(java, command, NULL, NULL, FALSE, 0, NULL, game, &startup, &process))
    {
        MessageBoxW(NULL, L"Impossible de lancer le jeu : le dossier runtime ou game est introuvable.\n"
            L"Garde BrainOut.exe dans le dossier BrainOut, avec runtime et game.", L"BrainOut", MB_ICONERROR);
        return 1;
    }

    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return 0;
}
