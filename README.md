# FprimeYamcsReference F´ project

This is a basic project that shows F Prime/YAMCS integration. It has two key features:

1. It uses `Drv.Udp` as the communication driver
2. It has YAMCS and F Prime/YAMCS packages in `requirements.txt`

## Setup

1. Initialize the fprime submodule:
   ```
   git submodule update --init lib/fprime
   ```
2. Create and activate a Python virtual environment:
   ```
   python3 -m venv fprime-venv
   source fprime-venv/bin/activate
   ```
3. Install dependencies:
   ```
   pip install -r requirements.txt
   ```

## Building

Building is done in the standard F Prime way:

1. `fprime-util generate`
2. `fprime-util build`

## Running

1. Activate the virtual environment: `source fprime-venv/bin/activate`
2. Launch the deployment and YAMCS server:
   ```
   fprime-yamcs -d /path/to/fprime-yamcs-reference/build-artifacts/Darwin/FprimeYamcsReference_YamcsDeployment --ip-address 127.0.0.1
   ```
   > **Note:** `--ip-address 127.0.0.1` is required so the F Prime binary sends UDP telemetry to localhost where YAMCS is listening. The default `0.0.0.0` will result in no telemetry appearing in YAMCS.
3. Open `http://localhost:8090` in your browser.