# Shortcuts on top of CMake. Requires: cmake >= 3.20, ninja, a C++20 compiler, libsqlite3 and libcurl.
PRESET ?= release

.PHONY: all build test run clean

all: build

build:
	cmake --preset $(PRESET)
	cmake --build --preset $(PRESET)

test: build
	ctest --preset $(PRESET)

run: build
	./build/$(PRESET)/search_engine_stage1

clean:
	rm -rf build
