#include "build-info.h"

int llama_build_number(void) {
    return 1;
}

const char * llama_commit(void) {
    return "bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3";
}

const char * llama_compiler(void) {
    return "clang";
}

const char * llama_build_target(void) {
    return "android";
}

const char * llama_build_info(void) {
    return "b1-bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3";
}

void llama_print_build_info(void) {
}
