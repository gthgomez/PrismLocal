#include "runtime/GenerationDrainAck.hpp"

#include <cstdio>

#define CHECK(value) do { if (!(value)) { \
    std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #value); \
    return 1; \
} } while (false)

int main() {
    using llmhost::validDrainAcknowledgement;
    CHECK(validDrainAcknowledgement(10, 10, 3, 8));
    CHECK(!validDrainAcknowledgement(9, 10, 3, 8)); // stale batch cannot eat the next batch
    CHECK(!validDrainAcknowledgement(10, 10, 9, 8)); // oversized delivery is rejected
    CHECK(!validDrainAcknowledgement(10, 10, 0, 8)); // no empty acknowledgements
    CHECK(!validDrainAcknowledgement(-1, 10, 1, 8));
    std::puts("generation drain acknowledgement is owner and range checked");
    return 0;
}
