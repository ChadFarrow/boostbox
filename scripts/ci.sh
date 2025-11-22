#!/usr/bin/env bash
sudo tar -C / -xf zstd.tar.gz
./zstd -d deps.tar.zst
sudo tar -C / -xf deps.tar
sudo cp /root/shell.sh "$HOME"
sudo cp -r /root/.config "$HOME"
sudo cp -r /root/.m2 "$HOME"
bash -c '. "$HOME"/shell.sh && ./bin/run_tests.sh'
