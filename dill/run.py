"""Run the single-process HTTP service behind Cloudflare on container port 8000."""

import uvicorn


def main():
    from app.main import app
    uvicorn.run(app, host="0.0.0.0", port=8000, log_config=None, access_log=False,
                ws_max_size=16384, ws_max_queue=16)


if __name__ == "__main__":
    main()
