#!/usr/bin/env bash
# Downloads the public datasets used to calibrate TwinGuard's models.
#  - OpenACC (EU JRC), AstaZero campaign. CC BY 4.0.
#    https://jeodpp.jrc.ec.europa.eu/ftp/jrc-opendata/TransportExpData/JRCDBT0001/LATEST/AstaZero/
#  - NASA PCoE Li-ion Battery Aging dataset. US government work.
#    https://phm-datasets.s3.amazonaws.com/NASA/5.+Battery+Data+Set.zip
set -euo pipefail
cd "$(dirname "$0")/.."
ACC=https://jeodpp.jrc.ec.europa.eu/ftp/jrc-opendata/TransportExpData/JRCDBT0001/LATEST/AstaZero
mkdir -p data/public/openacc_astazero data/public/nasa_battery
for f in ASta_040719_platoon{3..10}.csv ASta_050719_platoon{1,2}.csv AstaZero_notes.txt AstaZero_veh_specifications.csv copyright.txt; do
  [ -s "data/public/openacc_astazero/$f" ] || curl -fsSL -o "data/public/openacc_astazero/$f" "$ACC/$f"
done
Z=data/public/nasa_battery/battery_data_set.zip
[ -s "$Z" ] || curl -fsSL -o "$Z" "https://phm-datasets.s3.amazonaws.com/NASA/5.+Battery+Data+Set.zip"
echo "done"
