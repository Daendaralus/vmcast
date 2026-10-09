// Windowless build of vmcast for running at logon; logs to %LOCALAPPDATA%\vmcast\vmcast.log.
#![windows_subsystem = "windows"]

fn main() {
    vmcast::init_file_log();
    vmcast::run();
}
